package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.service.AnalysisRequestService;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
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
    private SessionCollectionRepository collectionRepository;

    @Autowired
    private MemberRepository memberRepository;

    @AfterEach
    void tearDown() {
        rawRepository.deleteAll();
        collectionRepository.deleteAll();
        sessionRepository.deleteAll();
        memberRepository.deleteAll();
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
    void AI_첫_호출이_실패해도_FAILED_로_두지_않고_종료_시각을_남긴다() {
        doThrow(new ResourceAccessException("connection refused")).when(aiClient).requestAnalysis(any());
        Long sessionId = session(SessionStatus.READY, Map.of("session", Map.of("endedAt", "2026-10-04T09:33:54Z")));

        service.requestAnalysis(sessionId);

        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
        assertThat(collection(sessionId).getAiRequestedAt()).isNotNull();
        assertThat(endedAt(sessionId)).isEqualTo(LocalDateTime.of(2026, 10, 4, 18, 33, 54));
    }

    @Test
    void 탈퇴한_회원의_세션은_AI_로_보내지_않고_FAILED_로_둔다() {
        Member withdrawn = memberRepository.save(new Member(SocialProvider.KAKAO, "withdrawn-1", null, null));
        withdrawn.withdraw(null, LocalDateTime.now());
        memberRepository.save(withdrawn);
        Long sessionId = session(SessionStatus.READY, RAW, withdrawn.getId());

        service.requestAnalysis(sessionId);

        verify(aiClient, never()).requestAnalysis(any());
        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void 요청에는_세션_id_만_담고_호출_시각을_남긴다() {
        Long sessionId = session(SessionStatus.READY);
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);

        service.requestAnalysis(sessionId);

        verify(aiClient).requestAnalysis(new AiAnalyzeRequest(sessionId));
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
        assertThat(collection(sessionId).getAiRequestedAt()).isAfter(before);
        assertThat(collection(sessionId).getAiResendCount()).isZero();
    }

    @Test
    void GET_이_1분_동안_없으면_재전송하고_응답이_있으면_계속_기다린다() {
        Long sessionId = analyzing();
        LocalDateTime requestedAt = collection(sessionId).getAiRequestedAt();

        service.resendIfDue(sessionId, requestedAt.plusSeconds(59));
        verify(aiClient, never()).requestAnalysis(any());

        service.resendIfDue(sessionId, requestedAt.plusMinutes(1));
        service.resendIfDue(sessionId, requestedAt.plusMinutes(1).plusSeconds(30));
        service.resendIfDue(sessionId, requestedAt.plusMinutes(2));

        verify(aiClient, times(2)).requestAnalysis(new AiAnalyzeRequest(sessionId));
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
        assertThat(collection(sessionId).getAiResendCount()).isEqualTo(2);
        assertThat(collection(sessionId).getAiUnansweredCount()).isZero();
    }

    @Test
    void 재전송에_응답이_없으면_2분_뒤_다시_보내고_또_없으면_FAILED() {
        Long sessionId = analyzing();
        LocalDateTime requestedAt = collection(sessionId).getAiRequestedAt();
        doThrow(new ResourceAccessException("connection refused")).when(aiClient).requestAnalysis(any());

        service.resendIfDue(sessionId, requestedAt.plusMinutes(1));
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
        assertThat(collection(sessionId).getAiUnansweredCount()).isEqualTo(1);

        service.resendIfDue(sessionId, requestedAt.plusMinutes(2));
        verify(aiClient, times(1)).requestAnalysis(any());

        service.resendIfDue(sessionId, requestedAt.plusMinutes(3));
        verify(aiClient, times(2)).requestAnalysis(any());
        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(collection(sessionId).getFailedAt()).isNotNull();
        assertThat(rawRepository.findById(sessionId).orElseThrow().getExpiresAt()).isNotNull();
    }

    @Test
    void 응답이_돌아오면_없었던_횟수를_다시_센다() {
        Long sessionId = analyzing();
        LocalDateTime requestedAt = collection(sessionId).getAiRequestedAt();
        doThrow(new ResourceAccessException("timeout")).when(aiClient).requestAnalysis(any());
        service.resendIfDue(sessionId, requestedAt.plusMinutes(1));

        doNothing().when(aiClient).requestAnalysis(any());
        service.resendIfDue(sessionId, requestedAt.plusMinutes(3));
        assertThat(collection(sessionId).getAiUnansweredCount()).isZero();

        doThrow(new ResourceAccessException("timeout")).when(aiClient).requestAnalysis(any());
        service.resendIfDue(sessionId, requestedAt.plusMinutes(4));

        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
        assertThat(collection(sessionId).getAiUnansweredCount()).isEqualTo(1);
    }

    @Test
    void AI_가_계속_대기_중이어도_전환_후_30분이_지나면_FAILED() {
        Long sessionId = analyzing();
        LocalDateTime requestedAt = collection(sessionId).getAiRequestedAt();

        service.resendIfDue(sessionId, requestedAt.plusMinutes(29));
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
        clearInvocations(aiClient);

        service.resendIfDue(sessionId, requestedAt.plusMinutes(30));

        verify(aiClient, never()).requestAnalysis(any());
        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void GET_을_받았으면_재전송하지_않는다() {
        Long sessionId = analyzing();
        markInputFetched(sessionId, LocalDateTime.now());
        LocalDateTime requestedAt = collection(sessionId).getAiRequestedAt();

        service.resendIfDue(sessionId, requestedAt.plusMinutes(10));

        verify(aiClient, never()).requestAnalysis(any());
    }

    @Test
    void 재전송하는_사이_GET_이_오면_응답이_없어도_세지_않는다() {
        Long sessionId = analyzing();
        LocalDateTime requestedAt = collection(sessionId).getAiRequestedAt();
        doAnswer(invocation -> {
            markInputFetched(sessionId, LocalDateTime.now());
            throw new ResourceAccessException("read timed out");
        }).when(aiClient).requestAnalysis(any());

        service.resendIfDue(sessionId, requestedAt.plusMinutes(1));

        assertThat(collection(sessionId).getAiUnansweredCount()).isZero();
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    void GET_뒤_30분_동안_콜백이_없으면_FAILED() {
        Long sessionId = analyzing();
        LocalDateTime fetchedAt = LocalDateTime.of(2026, 11, 2, 10, 0);
        markInputFetched(sessionId, fetchedAt);

        service.failIfCallbackOverdue(sessionId, fetchedAt.plusMinutes(29));
        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);

        service.failIfCallbackOverdue(sessionId, fetchedAt.plusMinutes(30));
        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
        assertThat(collection(sessionId).getFailedAt()).isNotNull();
    }

    @Test
    void 끝난_세션에는_재전송도_콜백_시간_초과도_적용하지_않는다() {
        Long sessionId = session(SessionStatus.READY);
        rawRepository.deleteById(sessionId);
        service.requestAnalysis(sessionId);
        assertThat(status(sessionId)).isEqualTo(SessionStatus.FAILED);
        clearInvocations(aiClient);

        service.resendIfDue(sessionId, LocalDateTime.now().plusHours(1));
        service.failIfCallbackOverdue(sessionId, LocalDateTime.now().plusHours(1));

        verifyNoInteractions(aiClient);
    }

    @Test
    void AI_요청이_실패해도_재전송_규칙을_따르므로_상태는_ANALYZING_이다() {
        Long sessionId = session(SessionStatus.READY);
        doThrow(new ResourceAccessException("connection refused")).when(aiClient).requestAnalysis(any());

        service.requestAnalysis(sessionId);

        assertThat(status(sessionId)).isEqualTo(SessionStatus.ANALYZING);
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
        assertThat(collection(sessionId).getFailedAt()).isNotNull();
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

    // AI 호출까지 끝나 GET 을 기다리는 세션
    private Long analyzing() {
        Long sessionId = session(SessionStatus.READY);
        service.requestAnalysis(sessionId);
        clearInvocations(aiClient);
        return sessionId;
    }

    private SessionCollection collection(Long sessionId) {
        return collectionRepository.findById(sessionId).orElseThrow();
    }

    private void markInputFetched(Long sessionId, LocalDateTime fetchedAt) {
        SessionCollection collection = collection(sessionId);
        collection.markInputFetched(fetchedAt);
        collectionRepository.save(collection);
    }

    private LocalDateTime endedAt(Long sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow().getEndedAt();
    }

    private SessionStatus status(Long sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow().getStatus();
    }
}
