package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.domain.AiClusterModel;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.analysis.service.AnalysisRequestService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.ResourceAccessException;

// 수집 완료 → 커밋 뒤 비동기 요청을 확인하려고 테스트 트랜잭션을 쓰지 않는다 (만든 행은 매번 지운다)
@SpringBootTest
@ActiveProfiles("test")
class AnalysisRequestServiceTest {

    private static final Long USER_ID = 1L;
    private static final Map<String, Object> RAW = Map.of("kind", "rbc-session", "pages", List.of());

    @MockitoBean
    private AiAnalysisClient aiClient;

    @Autowired
    private AnalysisRequestService service;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private SessionRawPayloadRepository rawRepository;

    @Autowired
    private SessionResultRepository resultRepository;

    @Autowired
    private AiClusterModelRepository modelRepository;

    @AfterEach
    void tearDown() {
        rawRepository.deleteAll();
        resultRepository.deleteAll();
        modelRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    @Test
    void 새_세션은_수집_중이고_수집이_끝나면_커밋_뒤_AI_에_분석을_요청한다() {
        Long sessionId = session(null);
        assertThat(status(sessionId)).isEqualTo(SessionStatus.COLLECTING);

        service.finishCollection(sessionId);

        ArgumentCaptor<AiAnalyzeRequest> request = ArgumentCaptor.forClass(AiAnalyzeRequest.class);
        verify(aiClient, timeout(3000)).requestAnalysis(request.capture());
        assertThat(request.getValue().sessionId()).isEqualTo(sessionId);
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    void 분석을_시작하면_원본의_종료_시각을_KST_로_세션에_남긴다() {
        Long sessionId = session(SessionStatus.READY, Map.of(
                "session", Map.of("endedAt", "2026-10-04T09:33:54.171Z"),
                "pages", List.of(Map.of("meta", Map.of("endedAt", "2026-10-04T23:00:00Z")))));

        service.requestAnalysis(sessionId);

        assertThat(endedAt(sessionId)).isEqualTo(LocalDateTime.of(2026, 10, 4, 18, 33, 54, 171_000_000));
    }

    @Test
    void 원본에_세션_종료_시각이_없으면_페이지_종료_시각_중_가장_늦은_값을_쓴다() {
        Long sessionId = session(SessionStatus.READY, Map.of(
                "session", Map.of("sessionId", "abc"),
                "pages", List.of(
                        Map.of("meta", Map.of("endedAt", "2026-10-04T09:00:00Z")),
                        Map.of("meta", Map.of("endedAt", "2026-10-04T09:30:00Z")),
                        Map.of("meta", Map.of("url", "https://a.com")))));

        service.requestAnalysis(sessionId);

        assertThat(endedAt(sessionId)).isEqualTo(LocalDateTime.of(2026, 10, 4, 18, 30));
    }

    @Test
    void 원본에_종료_시각이_전혀_없으면_분석을_시작한_시각을_쓴다() {
        Long sessionId = session(SessionStatus.READY);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        service.requestAnalysis(sessionId);

        assertThat(endedAt(sessionId)).isAfter(before);
    }

    @Test
    void AI_요청이_실패해_FAILED_가_되어도_종료_시각은_남는다() {
        doThrow(new ResourceAccessException("connection refused")).when(aiClient).requestAnalysis(any());
        Long sessionId = session(SessionStatus.READY, Map.of("session", Map.of("endedAt", "2026-10-04T09:33:54Z")));

        service.requestAnalysis(sessionId);

        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(endedAt(sessionId)).isEqualTo(LocalDateTime.of(2026, 10, 4, 18, 33, 54));
    }

    @Test
    void 요청에_원본_이전_세션_피처_개인_전체_모델을_담는다() {
        Long sessionId = session(SessionStatus.READY);
        result(9001L, USER_ID, List.of(Map.of("session_id", "prev-1", "x", 1), Map.of("session_id", "prev-1", "x", 2)));
        result(9002L, 2L, List.of(Map.of("session_id", "other-user", "x", 3)));
        modelRepository.save(new AiClusterModel(ClusterModelScope.USER, USER_ID, Map.of("scope", "user"), 12, LocalDateTime.now()));
        modelRepository.save(new AiClusterModel(ClusterModelScope.GLOBAL, null, Map.of("scope", "global"), 50, LocalDateTime.now()));

        service.requestAnalysis(sessionId);

        ArgumentCaptor<AiAnalyzeRequest> captor = ArgumentCaptor.forClass(AiAnalyzeRequest.class);
        verify(aiClient).requestAnalysis(captor.capture());
        AiAnalyzeRequest request = captor.getValue();
        assertThat(request.session()).containsEntry("kind", "rbc-session");
        assertThat(request.history()).extracting(row -> row.get("x")).containsExactly(1, 2);
        assertThat(request.userModel()).containsEntry("scope", "user");
        assertThat(request.globalModel()).containsEntry("scope", "global");
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    void AI_요청이_실패하면_FAILED_로_바꾼다() {
        Long sessionId = session(SessionStatus.READY);
        doThrow(new ResourceAccessException("connection refused")).when(aiClient).requestAnalysis(any());

        service.requestAnalysis(sessionId);

        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void READY_가_아니면_요청하지_않는다() {
        Long sessionId = session(SessionStatus.ANALYZING);

        service.requestAnalysis(sessionId);

        verifyNoInteractions(aiClient);
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    void 원본이_없으면_요청하지_않고_FAILED_로_바꾼다() {
        Long sessionId = session(SessionStatus.READY);
        rawRepository.deleteById(sessionId);

        service.requestAnalysis(sessionId);

        verifyNoInteractions(aiClient);
        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void 수집_중이_아닌_세션은_수집_완료_처리하지_않는다() {
        Long sessionId = session(SessionStatus.READY);

        assertThatThrownBy(() -> service.finishCollection(sessionId))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SESSION_NOT_COLLECTING);
    }

    // status 가 null 이면 기본값(COLLECTING) 그대로
    private Long session(SessionStatus status) {
        return session(status, RAW);
    }

    private Long session(SessionStatus status, Map<String, Object> raw) {
        return session(status, raw, USER_ID);
    }

    private Long session(SessionStatus status, Map<String, Object> raw, Long userId) {
        BrowsingSession session = new BrowsingSession(userId, "세션", LocalDateTime.of(2026, 10, 30, 10, 0));
        if (status == SessionStatus.READY || status == SessionStatus.ANALYZING) {
            session.markReady();
        }
        if (status == SessionStatus.ANALYZING) {
            session.startAnalysis(LocalDateTime.of(2026, 10, 30, 10, 30));
        }
        Long sessionId = sessionRepository.save(session).getId();
        rawRepository.save(new SessionRawPayload(sessionId, userId, raw, LocalDateTime.now()));
        return sessionId;
    }

    private void result(Long sessionId, Long userId, List<Map<String, Object>> unitFeatures) {
        SessionResult result = new SessionResult(sessionId, userId);
        result.replace(null, List.of(), List.of(), Map.of(), List.of(), unitFeatures, "global", "C");
        resultRepository.save(result);
    }

    private LocalDateTime endedAt(Long sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow().getEndedAt();
    }

    private SessionStatus status(Long sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow().getStatus();
    }
}
