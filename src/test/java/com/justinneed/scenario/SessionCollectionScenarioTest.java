package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.service.RawPayloadCleanupScheduler;
import com.justinneed.session.chunk.service.ChunkAssembler;
import com.justinneed.session.chunk.service.ChunkSessionTimeoutScheduler;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.collection.service.CollectionRecoveryService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * 확장 청크 → 세션 → 합치기(다 왔을 때 · 마지막 청크 뒤 1시간) → AI 호출 → GET /input → 결과 → 청크 보관 · 삭제.
 * 확장은 시작 · 종료 요청을 보내지 않는다. 시작과 끝은 청크로 판단한다.
 * 커밋 뒤 비동기로 나가는 AI 요청을 봐야 해서 테스트 트랜잭션을 쓰지 않고 DB 를 따로 쓴다 (만든 행은 매번 지운다).
 * 시간이 지나야 하는 처리는 스케줄러 · 복구 서비스에 시각을 넘겨 돌린다.
 * 청크 = src/test/resources/chunk-assembly/tricky.chunks.json (2개, 2번이 final · stop 기록 있음). payload.memberId 만 이 테스트 회원으로 바꿔 보낸다.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:session-collection;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("세션 수집 (확장 청크 → 합치기 → AI)")
class SessionCollectionScenarioTest extends ScenarioTestSupport {

    private static final String COLLECTOR = "00000000-tric-ky00-0000-000000000001";
    private static final long START_TS = 1793600000000L;   // tricky 시작 기록의 ts
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String RESULT = """
            {"modelSource": "global", "scoreMethod": "C", "state": {"unitFeatures": []},
             "mindmap": {"structureSource": "llm",
                         "nodes": [{"id": "root:1", "type": "root", "keyword": "로마", "score": 1.0, "childCount": 0}],
                         "edges": []},
             "essay": {"source": "llm", "title": "분석한 제목", "sections": [{"heading": "소제목", "body": "본문"}]},
             "selected": [], "analysis": {"units": [], "summary": {}}, "pages": []}
            """;

    @MockitoBean
    private AiAnalysisClient aiClient;

    @Autowired
    private SessionRawPayloadRepository rawPayloads;

    @Autowired
    private SessionCollectionRepository collections;

    @Autowired
    private CollectionRecoveryService recovery;

    @Autowired
    private ChunkSessionTimeoutScheduler timeouts;

    @Autowired
    private RawPayloadCleanupScheduler cleanup;

    @Autowired
    private AiInternalToken internalToken;

    @Autowired
    private JdbcTemplate jdbc;

    private Member me;
    private List<Map<String, Object>> chunks;

    @BeforeEach
    void seed() throws Exception {
        me = member("seeker");
        try (InputStream in = getClass().getResourceAsStream("/chunk-assembly/tricky.chunks.json")) {
            chunks = objectMapper.readValue(in, new TypeReference<>() { });
        }
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("summaries", "session_results", "session_raw_payloads", "session_raw_chunks",
                "session_collections", "community_cards", "sessions", "members")) {
            jdbc.update("delete from " + table);
        }
    }

    // ---------- 요청 · 조회 ----------

    // n 번 청크 (memberId 를 회원으로 바꾼다)
    @SuppressWarnings("unchecked")
    private Map<String, Object> chunk(int n, Member owner) {
        var body = new HashMap<>(chunks.get(n - 1));
        var payload = new HashMap<>((Map<String, Object>) body.get("payload"));
        payload.put("memberId", owner.getId());
        body.put("payload", payload);
        return body;
    }

    private Map<String, Object> chunk(int n) {
        return chunk(n, me);
    }

    private ResultActions send(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/sessions/chunks").with(as(me)).with(json(body)));
    }

    private ResultActions send(int n) throws Exception {
        return send(chunk(n));
    }

    private ResultActions input(Long id) throws Exception {
        return mvc.perform(get("/internal/ai/sessions/{id}/input", id).header(AiInternalToken.HEADER, internalToken.value()));
    }

    private ResultActions result(Long id) throws Exception {
        return mvc.perform(post("/internal/ai/sessions/{id}/result", id)
                .header(AiInternalToken.HEADER, internalToken.value())
                .with(json(RESULT)));
    }

    private ResultActions failure(Long id) throws Exception {
        return mvc.perform(post("/internal/ai/sessions/{id}/failure", id)
                .header(AiInternalToken.HEADER, internalToken.value())
                .with(json(Map.of("reason", "boom"))));
    }

    private BrowsingSession row() {
        return sessions.findByUserIdAndCollectorSessionId(me.getId(), COLLECTOR).orElseThrow();
    }

    private Long sessionId() {
        return row().getId();
    }

    private SessionCollection collection() {
        return collections.findById(sessionId()).orElseThrow();
    }

    private void awaitStatus(SessionStatus status) {
        await().atMost(5, TimeUnit.SECONDS).until(() -> row().getStatus() == status);
    }

    private JsonNode storedBundle() {
        return objectMapper.valueToTree(rawPayloads.findById(sessionId()).orElseThrow().getPayload());
    }

    private JsonNode tricky() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/chunk-assembly/tricky.bundle.json")) {
            return objectMapper.readTree(in);
        }
    }

    private int storedChunks() {
        return jdbc.queryForObject("select count(*) from session_raw_chunks", Integer.class);
    }

    private static String today() {
        return LocalDate.now(KST).format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
    }

    // 다 와서 합쳐지고 AI 를 부를 때까지
    private void collectAll() throws Exception {
        send(1).andExpect(status().isCreated());
        send(2).andExpect(status().isCreated());
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
    }

    private LocalDateTime lastChunkAt() {
        return collection().getLastChunkAt();
    }

    // ---------- 세션 만들기 · 다 왔을 때 ----------

    @Test
    @DisplayName("마지막 청크가 먼저 오면 수집 중으로 기다리고, 1번이 오면 시작 시각을 고친 뒤 합쳐 AI 를 부른다")
    void finalFirstThenComplete() throws Exception {
        send(2).andExpect(status().isCreated());
        BrowsingSession waiting = row();
        assertThat(waiting.getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(waiting.getTitle()).isEqualTo(today() + "의 1번째 세션");
        assertThat(rawPayloads.existsById(waiting.getId())).isFalse();
        verify(aiClient, after(300).never()).requestAnalysis(any());

        send(1).andExpect(status().isCreated());

        verify(aiClient, timeout(5000)).requestAnalysis(new AiAnalyzeRequest(waiting.getId()));
        awaitStatus(SessionStatus.ANALYZING);
        assertThat(row().getStartedAt()).isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(START_TS), KST));
        // stop 기록이 있어 채우는 값이 없다 → 확장 assemble() 이 만든 정답과 같다
        assertThat(storedBundle()).isEqualTo(tricky());
        MvcResult fetched = input(waiting.getId()).andExpect(okEnvelope()).andReturn();
        JsonNode sent = objectMapper.readTree(fetched.getResponse().getContentAsString()).path("data").path("session");
        assertThat(sent).isEqualTo(tricky());
    }

    @Test
    @DisplayName("끝까지: 합쳐 AI 를 부르고, GET 과 결과를 거쳐 완료된다. 청크는 마지막 청크 뒤 3일까지 남았다가 지워진다")
    void fullFlow() throws Exception {
        collectAll();
        input(sessionId()).andExpect(okEnvelope());
        assertThat(collection().getInputFetchedAt()).isNotNull();

        result(sessionId()).andExpect(okEnvelope());

        BrowsingSession session = row();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getTitle()).isEqualTo("분석한 제목");
        assertThat(rawPayloads.findById(session.getId()).orElseThrow().getExpiresAt()).isNotNull();
        assertThat(storedChunks()).isEqualTo(2);

        LocalDateTime lastChunk = jdbc.queryForObject("select max(received_at) from session_raw_chunks", LocalDateTime.class);
        assertThat(timeouts.purgeCompletedChunks(lastChunk.plusDays(3).minusMinutes(1))).isZero();
        assertThat(timeouts.purgeCompletedChunks(lastChunk.plusDays(3).plusMinutes(1))).isEqualTo(2);
        assertThat(storedChunks()).isZero();
    }

    @Test
    @DisplayName("합친 뒤 같은 청크가 다시 오면 200 이고 AI 요청은 한 번뿐이다. 내용이 다르면 409")
    @SuppressWarnings("unchecked")
    void resendAfterComplete() throws Exception {
        collectAll();

        send(2).andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicate").value(true));
        send(1).andExpect(status().isOk());
        var changed = chunk(1);
        ((Map<String, Object>) changed.get("payload")).put("cutAt", "다른 값");
        send(changed).andExpect(status().isConflict());

        verify(aiClient, after(500).times(1)).requestAnalysis(any());
        assertThat(sessions.findAll().stream().filter(s -> COLLECTOR.equals(s.getCollectorSessionId()))).hasSize(1);
    }

    @Test
    @DisplayName("같은 세션 첫 청크가 동시에 와도 세션 · 청크는 하나다")
    void concurrentFirstChunk() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        Callable<Integer> post = () -> {
            ready.await();
            return send(1).andReturn().getResponse().getStatus();
        };

        List<Integer> codes = new ArrayList<>();
        try {
            List<Future<Integer>> running = new ArrayList<>(List.of(pool.submit(post), pool.submit(post)));
            ready.countDown();
            for (Future<Integer> task : running) {
                codes.add(task.get(20, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(codes).containsExactlyInAnyOrder(201, 200);
        assertThat(sessions.findAll().stream().filter(s -> COLLECTOR.equals(s.getCollectorSessionId()))).hasSize(1);
        assertThat(storedChunks()).isEqualTo(1);
    }

    @Test
    @DisplayName("임시 제목 번호는 오늘 만든 내 세션을 지운 것까지 세고, 다른 회원은 따로 센다")
    void titleNumbering() throws Exception {
        BrowsingSession removed = new BrowsingSession(me.getId(), "지운 세션", LocalDateTime.now());
        removed.delete();
        sessions.saveAndFlush(removed);
        Member other = member("other");
        mvc.perform(post("/sessions/chunks").with(as(other)).with(json(chunk(1, other)))).andExpect(status().isCreated());

        send(1).andExpect(status().isCreated());

        assertThat(row().getTitle()).isEqualTo(today() + "의 2번째 세션");
        assertThat(sessions.findByUserIdAndCollectorSessionId(other.getId(), COLLECTOR).orElseThrow().getTitle())
                .isEqualTo(today() + "의 1번째 세션");
    }

    @Test
    @DisplayName("청크가 다 왔어도 seq 가 끊기면 합치지 않고 기다리다가, 1시간 뒤 FAILED 로 바꾸고 실패 기록을 남긴다")
    @SuppressWarnings("unchecked")
    void brokenFailsAfterQuiet() throws Exception {
        var second = chunk(2);
        var payload = (Map<String, Object>) second.get("payload");
        var records = (List<Object>) payload.get("records");
        payload.put("fromSeq", ((Number) payload.get("fromSeq")).intValue() + 1);
        payload.put("records", records.subList(1, records.size()));

        send(1).andExpect(status().isCreated());
        send(second).andExpect(status().isCreated());
        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        verify(aiClient, after(300).never()).requestAnalysis(any());

        assertThat(timeouts.closeQuietSessions(lastChunkAt().plusHours(1))).isEqualTo(1);

        assertThat(row().getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(collection().getFailedAt()).isNotNull();
        assertThat(rawPayloads.existsById(sessionId())).isFalse();
        assertThat(storedChunks()).isEqualTo(2);
    }

    // ---------- 마지막 청크 뒤 1시간 ----------

    @Test
    @DisplayName("final 없이 1시간 조용하면 그때까지 온 청크로 합친다. 끝 시각은 마지막 기록 시각, 종료 사유는 timeout")
    @SuppressWarnings("unchecked")
    void quietWithoutFinal() throws Exception {
        send(1).andExpect(status().isCreated());

        assertThat(timeouts.closeQuietSessions(lastChunkAt().plusMinutes(59))).isZero();
        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);

        assertThat(timeouts.closeQuietSessions(lastChunkAt().plusMinutes(60))).isEqualTo(1);

        verify(aiClient, timeout(5000)).requestAnalysis(any());
        var records = (List<Map<String, Object>>) ((Map<String, Object>) chunk(1).get("payload")).get("records");
        JsonNode assembled = objectMapper.valueToTree(ChunkAssembler.assemble(records));
        JsonNode stored = storedBundle();
        assertThat(stored.path("pages").size()).isEqualTo(assembled.path("pages").size());
        assertThat(stored.path("pages").path(0).path("timeline")).isEqualTo(assembled.path("pages").path(0).path("timeline"));
        // stop 기록이 없어 null 이던 끝 시각을 세션 · 페이지 모두 마지막 기록 시각으로 채운다
        assertThat(assembled.path("session").path("endedAt").isNull()).isTrue();
        assertThat(stored.path("session").path("endedAt").asText()).isEqualTo("2026-11-02T06:13:29.000Z");
        assertThat(stored.path("pages").path(0).path("meta").path("endedAt").asText()).isEqualTo("2026-11-02T06:13:29.000Z");
        assertThat(stored.path("session").path("stopReason").asText()).isEqualTo("timeout");
        assertThat(stored.path("session").path("startedAt").asText()).isEqualTo("2026-11-02T06:13:20.000Z");
    }

    @Test
    @DisplayName("빈 번호가 있어도 1시간 뒤 받은 만큼 합쳐 분석한다. 시작 기록이 없으면 세션 행의 시작 시각을 쓴다")
    void quietWithGapIsAnalyzed() throws Exception {
        send(2).andExpect(status().isCreated());   // 1번이 없음
        LocalDateTime rowStartedAt = row().getStartedAt();

        assertThat(timeouts.closeQuietSessions(lastChunkAt().plusHours(1))).isEqualTo(1);

        verify(aiClient, timeout(5000)).requestAnalysis(new AiAnalyzeRequest(sessionId()));
        JsonNode session = storedBundle().path("session");
        assertThat(session.path("startedAt").asText()).isEqualTo(
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                        .format(rowStartedAt.atZone(KST).withZoneSameInstant(ZoneId.of("UTC"))));
        // 2번 청크 안의 stop 기록은 그대로 쓴다
        assertThat(session.path("stopReason").asText()).isEqualTo("idle");
        assertThat(session.path("sessionId").asText()).isEqualTo(COLLECTOR);
    }

    @Test
    @DisplayName("끝난 뒤 늦게 온 청크는 빈 번호를 채우더라도 저장만 하고 다시 합치거나 분석하지 않는다")
    void lateChunkIsStoredOnly() throws Exception {
        send(2).andExpect(status().isCreated());
        timeouts.closeQuietSessions(lastChunkAt().plusHours(1));
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        JsonNode before = storedBundle();

        send(1).andExpect(status().isCreated());

        verify(aiClient, after(500).times(1)).requestAnalysis(any());
        assertThat(storedChunks()).isEqualTo(2);
        assertThat(storedBundle()).isEqualTo(before);
        assertThat(row().getStatus()).isNotEqualTo(SessionStatus.COLLECTING);
    }

    @Test
    @DisplayName("확장 청크로 만들지 않은 수집 중 세션(수집기 id 없음)은 정리하지 않는다")
    void legacyCollectingSessionIsLeftAlone() {
        BrowsingSession legacy = sessionWithStatus(me, "옛 세션", "2026-01-01T09:00:00", null,
                SessionStatus.COLLECTING, List.of());

        timeouts.closeQuietSessions(LocalDateTime.now().plusDays(1));

        assertThat(sessions.findById(legacy.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.COLLECTING);
    }

    // ---------- 복구 · 재전송 ----------

    @Test
    @DisplayName("READY 인데 AI 호출이 안 나간 세션은 복구 스케줄러가 부른다")
    void readyIsRecovered() {
        BrowsingSession session = new BrowsingSession(me.getId(), "세션", LocalDateTime.now());
        session.markReady();
        Long id = sessions.saveAndFlush(session).getId();
        rawPayloads.save(new SessionRawPayload(id, me.getId(), Map.of("kind", "rbc-session"), LocalDateTime.now()));

        recovery.run(LocalDateTime.now());

        verify(aiClient).requestAnalysis(new AiAnalyzeRequest(id));
        assertThat(sessions.findById(id).orElseThrow().getStatus()).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    @DisplayName("AI 가 응답하지 않으면 복구 스케줄러가 재전송 뒤 FAILED 로 바꾸고 실패 기록을 남긴다")
    void resendFromScheduler() throws Exception {
        doThrow(new ResourceAccessException("connection refused")).when(aiClient).requestAnalysis(any());
        collectAll();
        LocalDateTime requestedAt = collection().getAiRequestedAt();

        recovery.run(requestedAt.plusMinutes(1));
        assertThat(row().getStatus()).isEqualTo(SessionStatus.ANALYZING);

        recovery.run(requestedAt.plusMinutes(3));

        verify(aiClient, times(3)).requestAnalysis(any());
        assertThat(row().getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(collection().getFailedAt()).isNotNull();
        assertThat(rawPayloads.findById(sessionId()).orElseThrow().getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("GET 뒤 콜백이 오지 않으면 복구 스케줄러가 30분 뒤 FAILED 로 바꾼다")
    void callbackTimeoutFromScheduler() throws Exception {
        collectAll();
        input(sessionId()).andExpect(okEnvelope());
        LocalDateTime fetchedAt = collection().getInputFetchedAt();

        recovery.run(fetchedAt.plusMinutes(29));
        assertThat(row().getStatus()).isEqualTo(SessionStatus.ANALYZING);

        recovery.run(fetchedAt.plusMinutes(30));
        assertThat(row().getStatus()).isEqualTo(SessionStatus.FAILED);
    }

    // ---------- 실패 · 재분석 · 보관 ----------

    @Test
    @DisplayName("실패한 세션의 청크는 실패 후 21일까지 남고, 그 뒤 지워진다. 분석 성공 세션의 청크는 이 정리에서 지우지 않는다")
    void failedChunksArePurgedAfter21Days() throws Exception {
        collectAll();
        failure(sessionId()).andExpect(okEnvelope());
        assertThat(row().getStatus()).isEqualTo(SessionStatus.FAILED);

        cleanup.purgeExpiredPayloads();
        assertThat(storedChunks()).isEqualTo(2);

        jdbc.update("update session_collections set failed_at = ?", LocalDateTime.now().minusDays(22));
        cleanup.purgeExpiredPayloads();
        assertThat(storedChunks()).isZero();
    }

    @Test
    @DisplayName("실패 뒤 재분석에 성공해도 청크는 바로 지우지 않는다")
    void reanalysisKeepsChunks() throws Exception {
        collectAll();
        failure(sessionId()).andExpect(okEnvelope());
        assertThat(rawPayloads.findById(sessionId()).orElseThrow().getExpiresAt()).isNotNull();

        mvc.perform(post("/sessions/{id}/reanalyze", sessionId()).with(as(me))).andExpect(okEnvelope());
        verify(aiClient, timeout(5000).times(2)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        assertThat(rawPayloads.findById(sessionId()).orElseThrow().getExpiresAt()).isNull();

        result(sessionId()).andExpect(okEnvelope());

        assertThat(row().getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(storedChunks()).isEqualTo(2);
    }

    // ---------- 삭제 ----------

    @Test
    @DisplayName("수집 중 세션을 지우면 청크도 지우고, 이후 청크는 저장하지 않고 200(ignored) 으로 답하며 정리도 돌지 않는다")
    void deleteWhileCollecting() throws Exception {
        send(1).andExpect(status().isCreated());

        mvc.perform(delete("/sessions/{id}", sessionId()).with(as(me))).andExpect(okEnvelope());
        assertThat(storedChunks()).isZero();

        send(2).andExpect(status().isOk()).andExpect(jsonPath("$.data.ignored").value(true));
        timeouts.closeQuietSessions(LocalDateTime.now().plusHours(2));

        verify(aiClient, after(300).never()).requestAnalysis(any());
        assertThat(rawPayloads.existsById(sessionId())).isFalse();
        assertThat(storedChunks()).isZero();
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("지운 세션은 복구 스케줄러가 재전송 · 실패 처리하지 않는다")
    void deletedSessionIsSkippedByRecovery() throws Exception {
        collectAll();
        mvc.perform(delete("/sessions/{id}", sessionId()).with(as(me))).andExpect(okEnvelope());

        recovery.run(LocalDateTime.now().plusHours(2));

        verify(aiClient, times(1)).requestAnalysis(any());
        assertThat(row().getStatus()).isEqualTo(SessionStatus.ANALYZING);
    }
}
