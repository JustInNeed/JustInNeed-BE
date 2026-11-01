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
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.collection.service.CollectionRecoveryService;
import com.justinneed.session.collection.service.SessionMergeService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;

/**
 * 세션 시작 → 청크 → 종료(요청 · 자동) → 합치기 → AI 호출 → GET → 결과 → 청크 삭제.
 * 커밋 뒤 비동기로 도는 합치기 · AI 요청을 봐야 해서 테스트 트랜잭션을 쓰지 않고 DB 를 따로 쓴다 (만든 행은 매번 지운다).
 * 시간이 지나야 하는 처리는 복구 서비스에 시각을 넘겨 돌린다.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:session-collection;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("세션 수집")
class SessionCollectionScenarioTest extends ScenarioTestSupport {

    private static final String COLLECTOR_ID = "ee30ed8c-5fb3-4cfc-aa4a-107b573409c6";
    private static final String STARTED_AT = "2026-10-30T16:44:04.406Z";
    private static final LocalDateTime STARTED_AT_KST = LocalDateTime.of(2026, 10, 31, 1, 44, 4, 406_000_000);
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
    private SessionRawChunkRepository chunks;

    @Autowired
    private SessionRawPayloadRepository rawPayloads;

    @Autowired
    private SessionCollectionRepository collections;

    @Autowired
    private CollectionRecoveryService recovery;

    @Autowired
    private SessionMergeService merges;

    @Autowired
    private AiInternalToken internalToken;

    @Autowired
    private JdbcTemplate jdbc;

    private Member me;

    @BeforeEach
    void seed() {
        me = member("seeker");
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("summaries", "session_results", "session_raw_payloads", "session_raw_chunks",
                "session_collections", "community_cards", "sessions", "members")) {
            jdbc.update("delete from " + table);
        }
    }

    // ---------- 요청 ----------

    private ResultActions start(String collectorSessionId, String startedAt) throws Exception {
        return mvc.perform(post("/sessions/collector/start").with(as(me))
                .with(json(Map.of("collectorSessionId", collectorSessionId, "startedAt", startedAt))));
    }

    private ResultActions end(String endedAt, String stopReason, int totalChunks) throws Exception {
        return mvc.perform(post("/sessions/collector/{id}/end", COLLECTOR_ID).with(as(me))
                .with(json(Map.of("endedAt", endedAt, "stopReason", stopReason, "totalChunks", totalChunks))));
    }

    private ResultActions chunk(String chunkId, Map<String, Object> payload) throws Exception {
        return mvc.perform(post("/sessions/chunks").with(as(me))
                .with(json(Map.of("sessionId", COLLECTOR_ID, "chunkId", chunkId, "payload", payload))));
    }

    private ResultActions result(Long id) throws Exception {
        return mvc.perform(post("/internal/ai/sessions/{id}/result", id)
                .header(AiInternalToken.HEADER, internalToken.value())
                .with(json(RESULT)));
    }

    // 공통 값이 든 첫 청크
    private static Map<String, Object> firstChunk() {
        return Map.of("kind", "rbc-session", "bundleVersion", 1,
                "session", Map.of("sessionId", COLLECTOR_ID, "startedAt", STARTED_AT, "tickMs", 150),
                "pages", List.of(page(1500)));
    }

    private static Map<String, Object> laterChunk() {
        return Map.of("pages", List.of(page(3000)));
    }

    private static Map<String, Object> page(int t) {
        return Map.of("meta", Map.of("pageId", "https://a.com"),
                "timeline", List.of(Map.of("type", "tick", "t", t, "segId", "seg-1")));
    }

    private BrowsingSession row() {
        return sessions.findByUserIdAndCollectorSessionId(me.getId(), COLLECTOR_ID).orElseThrow();
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergedSession() {
        return (Map<String, Object>) rawPayloads.findById(sessionId()).orElseThrow().getPayload().get("session");
    }

    private long storedChunks() {
        return chunks.countByUserIdAndCollectorSessionId(me.getId(), COLLECTOR_ID);
    }

    private static String today() {
        return LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
    }

    // ---------- 시작 ----------

    @Test
    @DisplayName("시작하면 수집 중 세션을 만들고 임시 제목을 붙이며 201 로 답한다")
    void startCreatesCollectingSession() throws Exception {
        start(COLLECTOR_ID, STARTED_AT)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("COLLECTING"))
                .andExpect(jsonPath("$.data.collectorSessionId").value(COLLECTOR_ID))
                .andExpect(jsonPath("$.data.created").value(true));

        BrowsingSession session = row();
        assertThat(session.getStartedAt()).isEqualTo(STARTED_AT_KST);
        assertThat(session.getTitle()).isEqualTo(today() + "의 1번째 세션");
    }

    @Test
    @DisplayName("시작을 다시 보내면 같은 세션을 돌려주고 시작 시각도 바꾸지 않는다")
    void startIsIdempotent() throws Exception {
        Long id = readLong(start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated()), "$.data.sessionId");

        start(COLLECTOR_ID, "2026-10-30T17:00:00Z")
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessionId").value(id))
                .andExpect(jsonPath("$.data.created").value(false));

        assertThat(sessions.count()).isEqualTo(1);
        assertThat(row().getStartedAt()).isEqualTo(STARTED_AT_KST);
    }

    @Test
    @DisplayName("동시에 시작해도 세션은 하나다")
    void concurrentStart() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        Callable<Integer> request = () -> {
            ready.await();
            return start(COLLECTOR_ID, STARTED_AT).andReturn().getResponse().getStatus();
        };

        try {
            List<Future<Integer>> responses = new ArrayList<>(List.of(pool.submit(request), pool.submit(request)));
            ready.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : responses) {
                statuses.add(response.get(20, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("임시 제목 번호는 오늘 만든 내 세션을 지운 것까지 세고, 다른 회원은 따로 센다")
    void titleNumbering() throws Exception {
        start("first", STARTED_AT).andExpect(status().isCreated());
        Long second = readLong(start("second", STARTED_AT), "$.data.sessionId");
        mvc.perform(delete("/sessions/{id}", second).with(as(me))).andExpect(okEnvelope());

        start("third", STARTED_AT)
                .andExpect(jsonPath("$.data.title").value(today() + "의 3번째 세션"));

        Member other = member("other");
        mvc.perform(post("/sessions/collector/start").with(as(other))
                        .with(json(Map.of("collectorSessionId", "first", "startedAt", STARTED_AT))))
                .andExpect(jsonPath("$.data.title").value(today() + "의 1번째 세션"));
    }

    @Test
    @DisplayName("청크가 시작 요청보다 먼저 와서 만든 세션은 늦은 시작 요청의 시각으로 고친다. 그 뒤로는 고치지 않는다")
    void lateStartCorrectsStartedAt() throws Exception {
        chunk("a", laterChunk()).andExpect(status().isCreated());
        assertThat(row().getStartedAt()).isNotEqualTo(STARTED_AT_KST);

        start(COLLECTOR_ID, STARTED_AT).andExpect(okEnvelope()).andExpect(jsonPath("$.data.created").value(false));
        assertThat(row().getStartedAt()).isEqualTo(STARTED_AT_KST);

        start(COLLECTOR_ID, "2026-10-30T17:00:00Z").andExpect(okEnvelope());
        assertThat(row().getStartedAt()).isEqualTo(STARTED_AT_KST);
        assertThat(sessions.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("시작 · 종료 요청 형식이 틀리면 400, 로그인하지 않으면 401")
    void invalidRequests() throws Exception {
        mvc.perform(post("/sessions/collector/start").with(as(me)).with(json(Map.of("startedAt", STARTED_AT))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "수집기 세션 id 를 입력해 주세요."));
        mvc.perform(post("/sessions/collector/start").with(as(me)).with(json(Map.of("collectorSessionId", "a"))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "시작 시각(startedAt)을 입력해 주세요."));
        mvc.perform(post("/sessions/collector/{id}/end", COLLECTOR_ID).with(as(me))
                        .with(json(Map.of("endedAt", STARTED_AT, "stopReason", "user", "totalChunks", -1))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "총 청크 수(totalChunks)는 0 이상이어야 합니다."));
        mvc.perform(post("/sessions/collector/{id}/end", "x".repeat(65)).with(as(me))
                        .with(json(Map.of("endedAt", STARTED_AT, "stopReason", "user", "totalChunks", 1))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "수집기 세션 id 는 64자 이하여야 합니다."));
        mvc.perform(post("/sessions/collector/start")
                        .with(json(Map.of("collectorSessionId", "a", "startedAt", STARTED_AT))))
                .andExpect(status().isUnauthorized());

        assertThat(sessions.count()).isZero();
    }

    // ---------- 종료 ----------

    @Test
    @DisplayName("끝까지: 청크가 총 수만큼 있으면 종료 요청 뒤 바로 합쳐 AI 를 부르고, GET 과 결과를 거쳐 청크를 지운다")
    void fullFlow() throws Exception {
        start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated());
        chunk("a", firstChunk()).andExpect(status().isCreated());
        chunk("b", laterChunk()).andExpect(status().isCreated());

        end("2026-10-30T16:50:00.123Z", "user", 2)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessionId").value(sessionId()));

        verify(aiClient, timeout(5000)).requestAnalysis(new AiAnalyzeRequest(sessionId()));
        awaitStatus(SessionStatus.ANALYZING);
        assertThat(mergedSession())
                .containsEntry("endedAt", "2026-10-30T16:50:00.123Z")
                .containsEntry("stopReason", "user")
                .containsEntry("focusMs", 300);
        assertThat(row().getEndedAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 1, 50, 0, 123_000_000));
        assertThat(collection().getEndRequestedAt()).isNotNull();
        assertThat(storedChunks()).isEqualTo(2);

        mvc.perform(get("/internal/ai/sessions/{id}/input", sessionId())
                        .header(AiInternalToken.HEADER, internalToken.value()))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.session.pages[0].timeline.length()").value(2));
        assertThat(collection().getInputFetchedAt()).isNotNull();

        result(sessionId()).andExpect(okEnvelope());

        BrowsingSession session = row();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getTitle()).isEqualTo("분석한 제목");
        assertThat(storedChunks()).isZero();
        assertThat(rawPayloads.findById(session.getId()).orElseThrow().getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("청크가 적으면 3분을 기다리고, 그 뒤 받은 만큼 합친다")
    void missingChunksWait() throws Exception {
        start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated());
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 3).andExpect(okEnvelope());
        LocalDateTime requestedAt = collection().getEndRequestedAt();

        recovery.run(requestedAt.plusMinutes(2));
        verify(aiClient, after(300).never()).requestAnalysis(any());
        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);

        recovery.run(requestedAt.plusMinutes(3));

        // 앞당긴 시각을 넘겨서 같은 실행의 재전송까지 나갈 수 있다
        verify(aiClient, timeout(5000).atLeastOnce()).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        assertThat(mergedSession()).containsEntry("focusMs", 150);
    }

    @Test
    @DisplayName("기다리는 동안 늦은 청크로 총 수가 차면 바로 합친다")
    void lateChunkCompletes() throws Exception {
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 2).andExpect(okEnvelope());
        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);

        chunk("b", laterChunk()).andExpect(status().isCreated());

        verify(aiClient, timeout(5000)).requestAnalysis(any());
        assertThat(mergedSession()).containsEntry("focusMs", 300);
    }

    @Test
    @DisplayName("종료를 다시 보내도 처음 값을 유지하고 AI 호출은 한 번이다")
    void endIsIdempotent() throws Exception {
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 2).andExpect(okEnvelope());
        end("2026-10-30T16:59:00Z", "idle", 1).andExpect(okEnvelope());
        chunk("b", laterChunk()).andExpect(status().isCreated());

        verify(aiClient, timeout(5000)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        end("2026-10-30T17:00:00Z", "idle", 2).andExpect(okEnvelope());

        verify(aiClient, after(300).times(1)).requestAnalysis(any());
        assertThat(mergedSession()).containsEntry("endedAt", "2026-10-30T16:50:00Z")
                .containsEntry("stopReason", "user");
    }

    @Test
    @DisplayName("시작도 청크도 없이 종료만 오면 세션을 만들고, 청크 0개라 삭제한다. 늦은 청크는 저장하지 않고 세션도 되살리지 않는다")
    void endWithoutAnything() throws Exception {
        end("2026-10-30T16:50:00Z", "user", 0).andExpect(okEnvelope());

        await().atMost(5, TimeUnit.SECONDS).until(() -> row().isDeleted());
        verify(aiClient, never()).requestAnalysis(any());

        chunk("a", firstChunk())
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.ignored").value(true));
        assertThat(storedChunks()).isZero();
        assertThat(sessions.count()).isEqualTo(1);
        assertThat(row().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("종료 요청의 총 수만큼 청크가 끝내 안 오고 0개면 3분 뒤 삭제한다")
    void endWithNoChunkReceived() throws Exception {
        start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 2).andExpect(okEnvelope());

        recovery.run(collection().getEndRequestedAt().plusMinutes(3));

        assertThat(row().isDeleted()).isTrue();
        assertThat(rawPayloads.findById(sessionId())).isEmpty();
    }

    @Test
    @DisplayName("합친 뒤 온 종료 요청 · 늦은 청크는 2xx 로 답하고 아무것도 바꾸지 않는다")
    void afterMerge() throws Exception {
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 1).andExpect(okEnvelope());
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);

        chunk("b", laterChunk()).andExpect(okEnvelope()).andExpect(jsonPath("$.data.ignored").value(true));
        end("2026-10-30T16:59:00Z", "idle", 2).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.status").value("ANALYZING"));

        assertThat(storedChunks()).isEqualTo(1);
        assertThat(collection().getTotalChunks()).isEqualTo(1);
    }

    // ---------- 자동 종료 ----------

    @Test
    @DisplayName("종료 요청 없이 마지막 청크 후 30분이 지나면 timeout 으로 합친다. endedAt 은 마지막 틱 시각이다")
    void autoEnd() throws Exception {
        start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated());
        chunk("a", firstChunk()).andExpect(status().isCreated());
        chunk("b", laterChunk()).andExpect(status().isCreated());
        LocalDateTime lastChunkAt = collection().getLastChunkAt();

        recovery.run(lastChunkAt.plusMinutes(29));
        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);

        recovery.run(lastChunkAt.plusMinutes(30));

        verify(aiClient, timeout(5000).atLeastOnce()).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        assertThat(mergedSession())
                .containsEntry("stopReason", "timeout")
                .containsEntry("endedAt", "2026-10-30T16:44:07.406Z");
        assertThat(row().getEndedAt()).isEqualTo(STARTED_AT_KST.plusSeconds(3));
    }

    @Test
    @DisplayName("청크가 없으면 시작 시각부터 30분 뒤 자동 종료하고 세션을 삭제한다")
    void autoEndWithoutChunks() throws Exception {
        start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated());

        recovery.run(STARTED_AT_KST.plusMinutes(29));
        assertThat(row().isDeleted()).isFalse();

        recovery.run(STARTED_AT_KST.plusMinutes(30));

        assertThat(row().isDeleted()).isTrue();
        verify(aiClient, never()).requestAnalysis(any());
    }

    @Test
    @DisplayName("스케줄러가 고른 뒤 새 청크가 오면 잠금 안에서 다시 판단해 합치지 않는다")
    void chunkAfterSelection() throws Exception {
        chunk("a", firstChunk()).andExpect(status().isCreated());
        LocalDateTime lastChunkAt = collection().getLastChunkAt();

        merges.merge(sessionId(), lastChunkAt.plusMinutes(29));

        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);
    }

    @Test
    @DisplayName("이 기능 전에 만든 수집 중 세션(진행 기록 없음)은 자동 종료하지 않는다")
    void legacyCollectingSessionIsLeftAlone() {
        BrowsingSession legacy = sessionWithStatus(me, "옛 세션", "2026-01-01T09:00:00", null,
                SessionStatus.COLLECTING, List.of());

        recovery.run(LocalDateTime.now());

        BrowsingSession found = sessions.findById(legacy.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(found.isDeleted()).isFalse();
    }

    // ---------- 합치기 실패 · 동시 실행 ----------

    @Test
    @DisplayName("원본 형식이 깨졌으면 롤백하고 3번째에 FAILED 로 바꾼다. 원본은 없고 청크는 남는다")
    void brokenChunkFailsAfterThreeAttempts() throws Exception {
        chunk("a", Map.of("pages", "깨진 값")).andExpect(status().isCreated());
        LocalDateTime due = collection().getLastChunkAt().plusMinutes(30);

        recovery.run(due);
        recovery.run(due);
        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(collection().getMergeAttempts()).isEqualTo(2);

        recovery.run(due);

        assertThat(row().getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(collection().getFailedAt()).isNotNull();
        assertThat(rawPayloads.findById(sessionId())).isEmpty();
        assertThat(storedChunks()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 세션을 동시에 합쳐도 한 번만 합치고 AI 호출도 한 번이다")
    void concurrentMerge() throws Exception {
        chunk("a", firstChunk()).andExpect(status().isCreated());
        Long id = sessionId();
        LocalDateTime due = collection().getLastChunkAt().plusMinutes(30);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(1);
        Callable<Void> merge = () -> {
            ready.await();
            merges.merge(id, due);
            return null;
        };

        try {
            List<Future<Void>> running = new ArrayList<>(List.of(pool.submit(merge), pool.submit(merge)));
            ready.countDown();
            for (Future<Void> task : running) {
                task.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        verify(aiClient, after(500).times(1)).requestAnalysis(any());
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
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 1).andExpect(okEnvelope());
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
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
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 1).andExpect(okEnvelope());
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        mvc.perform(get("/internal/ai/sessions/{id}/input", sessionId())
                        .header(AiInternalToken.HEADER, internalToken.value()))
                .andExpect(okEnvelope());
        LocalDateTime fetchedAt = collection().getInputFetchedAt();

        recovery.run(fetchedAt.plusMinutes(29));
        assertThat(row().getStatus()).isEqualTo(SessionStatus.ANALYZING);

        recovery.run(fetchedAt.plusMinutes(30));
        assertThat(row().getStatus()).isEqualTo(SessionStatus.FAILED);
    }

    // ---------- 삭제 ----------

    @Test
    @DisplayName("수집 중 세션을 지우면 청크도 지우고, 이후 합치기가 돌지 않으며 늦은 청크 · 종료 요청도 무시한다")
    void deleteWhileCollecting() throws Exception {
        start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated());
        chunk("a", firstChunk()).andExpect(status().isCreated());

        mvc.perform(delete("/sessions/{id}", sessionId()).with(as(me))).andExpect(okEnvelope());
        assertThat(storedChunks()).isZero();

        chunk("b", laterChunk()).andExpect(okEnvelope()).andExpect(jsonPath("$.data.ignored").value(true));
        end("2026-10-30T16:50:00Z", "user", 1).andExpect(okEnvelope());
        recovery.run(LocalDateTime.now().plusHours(1));

        verify(aiClient, after(300).never()).requestAnalysis(any());
        assertThat(rawPayloads.findById(sessionId())).isEmpty();
        assertThat(storedChunks()).isZero();
        assertThat(row().getStatus()).isEqualTo(SessionStatus.COLLECTING);
    }

    @Test
    @DisplayName("지운 세션은 복구 스케줄러가 재전송 · 실패 처리하지 않는다")
    void deletedSessionIsSkippedByRecovery() throws Exception {
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 1).andExpect(okEnvelope());
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        mvc.perform(delete("/sessions/{id}", sessionId()).with(as(me))).andExpect(okEnvelope());

        recovery.run(LocalDateTime.now().plusHours(2));

        verify(aiClient, times(1)).requestAnalysis(any());
        assertThat(row().getStatus()).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    @DisplayName("실패한 세션의 청크는 남아 있다가 재분석에 성공하면 지워진다")
    void chunksStayUntilSuccessAfterReanalysis() throws Exception {
        chunk("a", firstChunk()).andExpect(status().isCreated());
        end("2026-10-30T16:50:00Z", "user", 1).andExpect(okEnvelope());
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        mvc.perform(post("/internal/ai/sessions/{id}/failure", sessionId())
                        .header(AiInternalToken.HEADER, internalToken.value())
                        .with(json(Map.of("reason", "boom"))))
                .andExpect(okEnvelope());
        assertThat(storedChunks()).isEqualTo(1);
        assertThat(rawPayloads.findById(sessionId()).orElseThrow().getExpiresAt()).isNotNull();

        mvc.perform(post("/sessions/{id}/reanalyze", sessionId()).with(as(me))).andExpect(okEnvelope());
        verify(aiClient, timeout(5000).times(2)).requestAnalysis(any());
        awaitStatus(SessionStatus.ANALYZING);
        assertThat(rawPayloads.findById(sessionId()).orElseThrow().getExpiresAt()).isNull();

        result(sessionId()).andExpect(okEnvelope());

        assertThat(storedChunks()).isZero();
    }

    @Test
    @DisplayName("시작 · 청크 · 종료는 다른 회원의 세션을 건드리지 않는다")
    void usersAreSeparate() throws Exception {
        start(COLLECTOR_ID, STARTED_AT).andExpect(status().isCreated());
        Member other = member("other");

        mvc.perform(post("/sessions/collector/{id}/end", COLLECTOR_ID).with(as(other))
                        .with(json(Map.of("endedAt", STARTED_AT, "stopReason", "user", "totalChunks", 0))))
                .andExpect(okEnvelope());

        await().atMost(5, TimeUnit.SECONDS).until(() -> sessions
                .findByUserIdAndCollectorSessionId(other.getId(), COLLECTOR_ID).orElseThrow().isDeleted());
        assertThat(row().isDeleted()).isFalse();
    }
}
