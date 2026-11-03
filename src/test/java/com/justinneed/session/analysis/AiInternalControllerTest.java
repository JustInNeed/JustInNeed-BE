package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.session.analysis.domain.AiClusterModel;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@DisplayName("AI 내부 조회 API (GET /internal/ai/sessions/{id}/input · /history)")
class AiInternalControllerTest {

    private static final String TOKEN = "test-ai-internal-token";
    // 다른 테스트가 커밋한 user_id=1 행과 섞이지 않게 쓴다
    private static final Long USER_ID = 7001L;
    private static final Long OTHER_USER_ID = 7002L;
    private static final Map<String, Object> RAW = Map.of("kind", "rbc-session", "pages", List.of());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private SessionRawPayloadRepository rawRepository;

    @Autowired
    private SessionResultRepository resultRepository;

    @Autowired
    private AiClusterModelRepository modelRepository;

    @Autowired
    private SessionCollectionRepository collectionRepository;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        sessionId = session(USER_ID, SessionStatus.ANALYZING).getId();
        rawRepository.save(new SessionRawPayload(sessionId, USER_ID, RAW, LocalDateTime.now()));
        collectionRepository.save(new SessionCollection(sessionId));
    }

    @Test
    @DisplayName("내부 토큰이 없거나 틀리면 401")
    void requiresToken() throws Exception {
        for (String path : List.of("input", "history")) {
            mockMvc.perform(get("/internal/ai/sessions/{id}/" + path, sessionId))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(get("/internal/ai/sessions/{id}/" + path, sessionId).header(AiInternalToken.HEADER, "wrong"))
                    .andExpect(status().isUnauthorized());
        }

        assertThat(collection().getInputFetchedAt()).isNull();
    }

    @Test
    @DisplayName("input 은 공통 envelope 의 data 로 원본 · 사용자 · 이전 세션 수 · 모델을 주고, 호출 시각을 남긴다")
    void inputShape() throws Exception {
        modelRepository.save(new AiClusterModel(ClusterModelScope.USER, USER_ID, Map.of("scope", "user"), 12, LocalDateTime.now()));
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        mockMvc.perform(input(sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.userId").value(USER_ID))
                .andExpect(jsonPath("$.data.session.kind").value("rbc-session"))
                .andExpect(jsonPath("$.data.previousSessions").value(0))
                .andExpect(jsonPath("$.data.userModel.scope").value("user"))
                .andExpect(jsonPath("$.data.unitFeatures").doesNotExist());

        assertThat(collection().getInputFetchedAt()).isAfter(before);
    }

    @Test
    @DisplayName("모델이 없으면 userModel 은 null")
    void noModel() throws Exception {
        mockMvc.perform(input(sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userModel").value(nullValue()));
    }

    @Test
    @DisplayName("previousSessions 는 현재 세션 · 지운 세션 · 다른 사용자의 결과를 세지 않는다")
    void previousSessions() throws Exception {
        result(sessionId, USER_ID, List.of());
        result(session(USER_ID, SessionStatus.COMPLETED).getId(), USER_ID, List.of());
        result(session(USER_ID, SessionStatus.COMPLETED).getId(), USER_ID, List.of());
        BrowsingSession deleted = session(USER_ID, SessionStatus.COMPLETED);
        result(deleted.getId(), USER_ID, List.of());
        deleted.delete();
        result(session(OTHER_USER_ID, SessionStatus.COMPLETED).getId(), OTHER_USER_ID, List.of());

        mockMvc.perform(input(sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previousSessions").value(2));
    }

    @Test
    @DisplayName("ANALYZING 이 아니면 409, 시각을 남기지 않는다")
    void notAnalyzing() throws Exception {
        for (SessionStatus status : List.of(SessionStatus.COLLECTING, SessionStatus.READY, SessionStatus.COMPLETED,
                SessionStatus.FAILED)) {
            Long id = session(USER_ID, status).getId();
            rawRepository.save(new SessionRawPayload(id, USER_ID, RAW, LocalDateTime.now()));

            mockMvc.perform(input(id)).andExpect(status().isConflict());

            assertThat(collectionRepository.findById(id)).isEmpty();
        }
    }

    @Test
    @DisplayName("지운 세션은 409")
    void deleted() throws Exception {
        sessionRepository.findById(sessionId).orElseThrow().delete();

        mockMvc.perform(input(sessionId)).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("없는 세션은 404")
    void notFound() throws Exception {
        mockMvc.perform(input(999_999L)).andExpect(status().isNotFound());
        mockMvc.perform(history(999_999L)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("원본이 없으면 FAILED 로 바꾸고 404, 실패 시각을 남긴다")
    void rawPayloadMissing() throws Exception {
        rawRepository.deleteById(sessionId);
        rawRepository.flush();

        mockMvc.perform(input(sessionId)).andExpect(status().isNotFound());

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(collection().getFailedAt()).isNotNull();
        assertThat(collection().getInputFetchedAt()).isNull();
    }

    @Test
    @DisplayName("history 는 이전 세션 유닛 피처를 주고 현재 세션 · 지운 세션 · 다른 사용자의 값은 뺀다")
    void historyShape() throws Exception {
        result(sessionId, USER_ID, List.of(Map.of("x", "current")));
        result(session(USER_ID, SessionStatus.COMPLETED).getId(), USER_ID,
                List.of(Map.of("x", "prev-1"), Map.of("x", "prev-2")));
        BrowsingSession deleted = session(USER_ID, SessionStatus.COMPLETED);
        result(deleted.getId(), USER_ID, List.of(Map.of("x", "deleted")));
        deleted.delete();
        result(session(OTHER_USER_ID, SessionStatus.COMPLETED).getId(), OTHER_USER_ID, List.of(Map.of("x", "other")));

        mockMvc.perform(history(sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.unitFeatures[*].x", containsInAnyOrder("prev-1", "prev-2")));
    }

    @Test
    @DisplayName("history 는 이전 세션이 없으면 빈 배열")
    void emptyHistory() throws Exception {
        mockMvc.perform(history(sessionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unitFeatures").isEmpty());
    }

    private MockHttpServletRequestBuilder input(Long id) {
        return get("/internal/ai/sessions/{id}/input", id).header(AiInternalToken.HEADER, TOKEN);
    }

    private MockHttpServletRequestBuilder history(Long id) {
        return get("/internal/ai/sessions/{id}/history", id).header(AiInternalToken.HEADER, TOKEN);
    }

    private SessionCollection collection() {
        return collectionRepository.findById(sessionId).orElseThrow();
    }

    private BrowsingSession session(Long userId, SessionStatus status) {
        BrowsingSession session = new BrowsingSession(userId, "세션", LocalDateTime.of(2026, 10, 30, 10, 0));
        switch (status) {
            case READY -> session.markReady();
            case ANALYZING -> session.startAnalysis(LocalDateTime.of(2026, 10, 30, 10, 30));
            case COMPLETED -> session.complete(LocalDateTime.of(2026, 10, 30, 10, 30), 1);
            case FAILED -> session.failAnalysis();
            default -> {
            }
        }
        return sessionRepository.saveAndFlush(session);
    }

    private void result(Long id, Long userId, List<Map<String, Object>> unitFeatures) {
        SessionResult result = new SessionResult(id, userId);
        result.replace(null, List.of(), List.of(), Map.of(), List.of(), unitFeatures, "global", "C");
        resultRepository.saveAndFlush(result);
    }
}
