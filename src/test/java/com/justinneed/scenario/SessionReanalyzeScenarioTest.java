package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.analysis.service.AnalysisRequestService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;

/**
 * POST /sessions/{id}/reanalyze 와 AI 요청 경로. 커밋 뒤 비동기로 나가는 요청을 봐야 해서 테스트 트랜잭션을 쓰지 않고 DB 를 따로 쓴다
 * (만든 행은 매번 지운다).
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:session-reanalyze;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("세션 재분석")
class SessionReanalyzeScenarioTest extends ScenarioTestSupport {

    private static final String NOT_FAILED = "분석에 실패한 세션만 다시 분석할 수 있습니다.";
    private static final Map<String, Object> RAW = Map.of("kind", "rbc-session", "pages", List.of());
    private static final String RESULT = """
            {"modelSource": "global", "scoreMethod": "C", "state": {"unitFeatures": []},
             "mindmap": {"structureSource": "llm",
                         "nodes": [{"id": "root:1", "type": "root", "keyword": "로마", "score": 1.0, "childCount": 0}],
                         "edges": []},
             "essay": {"source": "llm", "title": "다시 분석한 제목", "sections": [{"heading": "소제목", "body": "본문"}]},
             "selected": [], "analysis": {"units": [], "summary": {}}, "pages": []}
            """;

    @MockitoBean
    private AiAnalysisClient aiClient;

    @Autowired
    private AnalysisRequestService requests;

    @Autowired
    private SessionRawPayloadRepository rawPayloads;

    @Autowired
    private SessionResultRepository results;

    @Autowired
    private AiInternalToken internalToken;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Member me;

    @BeforeEach
    void seed() {
        me = member("sprout");
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("summaries", "session_results", "session_raw_payloads", "sessions", "members")) {
            jdbc.update("delete from " + table);
        }
    }

    private Long session(SessionStatus status, boolean raw) {
        Long id = sessionWithStatus(me, "실패한 세션", "2026-10-30T09:00:00", "2026-10-30T10:00:00", status, List.of()).getId();
        if (raw) {
            rawPayloads.save(new SessionRawPayload(id, me.getId(), RAW, LocalDateTime.now()));
        }
        return id;
    }

    private ResultActions reanalyze(Long id, Member viewer) throws Exception {
        return mvc.perform(post("/sessions/{id}/reanalyze", id).with(as(viewer)));
    }

    private SessionStatus status(Long id) {
        return sessions.findById(id).orElseThrow().getStatus();
    }

    @Test
    @DisplayName("실패한 세션을 다시 분석하면 READY 로 답하고, 커밋 뒤 AI 에 요청이 나가 ANALYZING 이 된다")
    void reanalyzeFailedSession() throws Exception {
        Long id = session(SessionStatus.FAILED, true);

        reanalyze(id, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.isOwner").value(true));

        ArgumentCaptor<AiAnalyzeRequest> request = ArgumentCaptor.forClass(AiAnalyzeRequest.class);
        verify(aiClient, timeout(3000)).requestAnalysis(request.capture());
        assertThat(request.getValue().sessionId()).isEqualTo(id);
        assertThat(request.getValue().session()).containsEntry("kind", "rbc-session");
        assertThat(status(id)).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    @DisplayName("다시 분석한 결과가 오면 COMPLETED 가 된다")
    void completesAfterReanalysis() throws Exception {
        Long id = session(SessionStatus.FAILED, true);
        reanalyze(id, me).andExpect(okEnvelope());
        verify(aiClient, timeout(3000)).requestAnalysis(any());

        mvc.perform(post("/internal/ai/sessions/{id}/result", id)
                        .header(AiInternalToken.HEADER, internalToken.value())
                        .with(json(RESULT)))
                .andExpect(okEnvelope());

        BrowsingSession session = sessions.findById(id).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getTitle()).isEqualTo("다시 분석한 제목");
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("실패한 세션이 아니면 409 이고 상태도 AI 요청도 그대로다")
    void onlyFailedSession(SessionStatus status) throws Exception {
        Long id = session(status, true);

        reanalyze(id, me).andExpect(errorEnvelope(HttpStatus.CONFLICT, NOT_FAILED));

        assertThat(status(id)).isEqualTo(status);
        verify(aiClient, after(200).never()).requestAnalysis(any());
    }

    @Test
    @DisplayName("다른 사용자 · 지운 세션 · 없는 세션은 404 다")
    void onlyOwner() throws Exception {
        Member other = member("foodtrip");
        Long id = session(SessionStatus.FAILED, true);
        Long deleted = session(SessionStatus.FAILED, true);
        mvc.perform(delete("/sessions/{id}", deleted).with(as(me))).andExpect(okEnvelope());

        reanalyze(id, other).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
        reanalyze(deleted, me).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
        reanalyze(999_999L, me).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));

        assertThat(status(id)).isEqualTo(SessionStatus.FAILED);
        verify(aiClient, after(200).never()).requestAnalysis(any());
    }

    @Test
    @DisplayName("원본이 없으면 404 '세션 원본을 찾을 수 없습니다.' 이고 FAILED 그대로다")
    void rawPayloadMissing() throws Exception {
        Long id = session(SessionStatus.FAILED, false);

        reanalyze(id, me).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션 원본을 찾을 수 없습니다."));

        assertThat(status(id)).isEqualTo(SessionStatus.FAILED);
        verify(aiClient, after(200).never()).requestAnalysis(any());
    }

    @Test
    @DisplayName("AI 요청이 실패하면 다시 FAILED 로 돌아간다")
    void aiRequestFails() throws Exception {
        doThrow(new ResourceAccessException("connection refused")).when(aiClient).requestAnalysis(any());
        Long id = session(SessionStatus.FAILED, true);

        reanalyze(id, me).andExpect(okEnvelope());

        verify(aiClient, timeout(3000)).requestAnalysis(any());
        await().atMost(3, TimeUnit.SECONDS)
                .until(() -> status(id) == SessionStatus.FAILED);
    }

    @Test
    @DisplayName("재분석을 두 번 눌러도 AI 요청은 한 번만 나간다 (하나는 409)")
    void doubleClick() throws Exception {
        Long id = session(SessionStatus.FAILED, true);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> click = () -> {
            start.await();
            return reanalyze(id, me).andReturn().getResponse().getStatus();
        };

        try {
            List<Future<Integer>> clicks = new ArrayList<>(List.of(pool.submit(click), pool.submit(click)));
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> response : clicks) {
                statuses.add(response.get(20, TimeUnit.SECONDS));
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        verify(aiClient, after(500).times(1)).requestAnalysis(any());
    }

    @Test
    @DisplayName("지운 세션은 READY 여도 AI 로 보내지 않는다")
    void deletedSessionIsNotSent() throws Exception {
        Long id = session(SessionStatus.READY, true);
        mvc.perform(delete("/sessions/{id}", id).with(as(me))).andExpect(okEnvelope());

        requests.requestAnalysis(id);

        verify(aiClient, never()).requestAnalysis(any());
        assertThat(status(id)).isEqualTo(SessionStatus.READY);
    }

    @Test
    @DisplayName("개인화 이력(history)에는 지운 세션의 결과가 들어가지 않는다")
    void historySkipsDeletedSessions() throws Exception {
        Long kept = session(SessionStatus.COMPLETED, false);
        Long removed = session(SessionStatus.COMPLETED, false);
        result(kept, Map.of("x", 1));
        result(removed, Map.of("x", 2));
        mvc.perform(delete("/sessions/{id}", removed).with(as(me))).andExpect(okEnvelope());
        Long id = session(SessionStatus.READY, true);

        requests.requestAnalysis(id);

        ArgumentCaptor<AiAnalyzeRequest> request = ArgumentCaptor.forClass(AiAnalyzeRequest.class);
        verify(aiClient, times(1)).requestAnalysis(request.capture());
        assertThat(request.getValue().history()).extracting(row -> row.get("x")).containsExactly(1);
    }

    @Test
    @DisplayName("AI 요청이 시간 초과로 실패해도 그 사이 온 결과(COMPLETED)를 FAILED 로 덮지 않는다")
    void lateRequestFailureKeepsCompletedResult() throws Exception {
        Long id = session(SessionStatus.READY, true);
        doAnswer(invocation -> {
            mvc.perform(post("/internal/ai/sessions/{id}/result", id)
                            .header(AiInternalToken.HEADER, internalToken.value())
                            .with(json(RESULT)))
                    .andExpect(okEnvelope());
            throw new ResourceAccessException("read timed out");
        }).when(aiClient).requestAnalysis(any());

        requests.requestAnalysis(id);

        assertThat(status(id)).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    @DisplayName("AI 요청 중에 지운 세션은 요청이 실패해도 상태를 바꾸지 않는다")
    void requestFailureLeavesDeletedSession() throws Exception {
        Long id = session(SessionStatus.READY, true);
        doAnswer(invocation -> {
            mvc.perform(delete("/sessions/{id}", id).with(as(me))).andExpect(okEnvelope());
            throw new ResourceAccessException("connection refused");
        }).when(aiClient).requestAnalysis(any());

        requests.requestAnalysis(id);

        assertThat(status(id)).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    @DisplayName("즐겨찾기 저장 뒤에 줄 선 재분석도 최신 행을 읽어 즐겨찾기를 덮지 않는다")
    void favouriteThenReanalyze() throws Exception {
        Long id = session(SessionStatus.FAILED, true);

        favouriteThen(id, () -> reanalyze(id, me).andExpect(okEnvelope()));

        verify(aiClient, timeout(3000)).requestAnalysis(any());
        await().atMost(3, TimeUnit.SECONDS).until(() -> status(id) == SessionStatus.ANALYZING);
        assertThat(sessions.findById(id).orElseThrow().isFavorite()).isTrue();
    }

    @Test
    @DisplayName("즐겨찾기 저장 뒤에 줄 선 수집 완료도 최신 행을 읽어 즐겨찾기를 덮지 않는다")
    void favouriteThenFinishCollection() throws Exception {
        Long id = session(SessionStatus.COLLECTING, true);

        favouriteThen(id, () -> {
            requests.finishCollection(id);
            return null;
        });

        await().atMost(3, TimeUnit.SECONDS).until(() -> status(id) == SessionStatus.ANALYZING);
        assertThat(sessions.findById(id).orElseThrow().isFavorite()).isTrue();
    }

    @Test
    @DisplayName("즐겨찾기 저장 뒤에 줄 선 분석 시작도 최신 행을 읽어 즐겨찾기를 덮지 않는다")
    void favouriteThenStartAnalysis() throws Exception {
        Long id = session(SessionStatus.READY, true);

        favouriteThen(id, () -> {
            requests.requestAnalysis(id);
            return null;
        });

        assertThat(status(id)).isEqualTo(SessionStatus.ANALYZING);
        assertThat(sessions.findById(id).orElseThrow().isFavorite()).isTrue();
    }

    @Test
    @DisplayName("즐겨찾기 저장 뒤에 줄 선 AI 요청 실패 처리도 최신 행을 읽어 즐겨찾기를 덮지 않는다")
    void favouriteThenRequestFailure() throws Exception {
        Long id = session(SessionStatus.READY, true);
        CountDownLatch analyzing = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        doAnswer(invocation -> {
            analyzing.countDown();
            fail.await(10, TimeUnit.SECONDS);
            throw new ResourceAccessException("connection refused");
        }).when(aiClient).requestAnalysis(any());
        ExecutorService requester = Executors.newSingleThreadExecutor();

        try {
            Future<?> request = requester.submit(() -> requests.requestAnalysis(id));
            assertThat(analyzing.await(5, TimeUnit.SECONDS)).isTrue();
            favouriteThen(id, () -> {
                fail.countDown();
                request.get(20, TimeUnit.SECONDS);
                return null;
            });
        } finally {
            fail.countDown();
            requester.shutdownNow();
        }

        assertThat(status(id)).isEqualTo(SessionStatus.FAILED);
        assertThat(sessions.findById(id).orElseThrow().isFavorite()).isTrue();
    }

    // 다른 트랜잭션이 행을 잡은 동안 즐겨찾기 저장을 먼저, writer 를 그 뒤에 세운 뒤 놓는다. 잠금 없이 옛 행을 읽은 writer 는 즐겨찾기를 덮는다
    private void favouriteThen(Long id, Callable<?> writer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> done = new ArrayList<>();
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("select id from sessions where id = ? for update", Long.class, id);
                done.add(pool.submit(() -> mvc.perform(patch("/sessions/{id}", id).with(as(me))
                        .with(json(Map.of("isFavorite", true)))).andExpect(okEnvelope())));
                pause();
                done.add(pool.submit(writer));
                pause();
            });
            for (Future<?> response : done) {
                response.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private void result(Long sessionId, Map<String, Object> feature) {
        SessionResult result = new SessionResult(sessionId, me.getId());
        result.replace(null, List.of(), List.of(), Map.of(), List.of(), List.of(feature), "global", "C");
        results.save(result);
    }
}
