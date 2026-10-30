package com.justinneed.session.analysis.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.domain.AiClusterModel;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

/**
 * Spring → AI 분석 요청.
 *
 * <pre>
 * 수집 완료 (finishCollection)  COLLECTING → READY, 커밋 뒤 비동기로 아래 실행
 * 분석 요청 (requestAnalysis)   READY → ANALYZING 커밋 → POST /analyze (AI 는 202 로 접수만)
 *                               요청이 실패하면 FAILED. 결과 · 실패는 AI 가 콜백으로 보낸다 (AiCallbackController)
 * </pre>
 */
@Service
public class AnalysisRequestService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisRequestService.class);

    private final BrowsingSessionRepository sessionRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;
    private final SessionResultRepository resultRepository;
    private final AiClusterModelRepository clusterModelRepository;
    private final AiAnalysisClient aiClient;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public AnalysisRequestService(BrowsingSessionRepository sessionRepository,
            SessionRawPayloadRepository rawPayloadRepository,
            SessionResultRepository resultRepository,
            AiClusterModelRepository clusterModelRepository,
            AiAnalysisClient aiClient,
            ApplicationEventPublisher eventPublisher,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.resultRepository = resultRepository;
        this.clusterModelRepository = clusterModelRepository;
        this.aiClient = aiClient;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // 확장 수집이 끝났을 때 호출 (청크 수신 · 완료 판정 쪽에서). 원본은 이 전에 session_raw_payloads 에 있어야 한다
    @Transactional
    public void finishCollection(Long sessionId) {
        BrowsingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        if (session.getStatus() != SessionStatus.COLLECTING) {
            throw new CustomException(ErrorCode.SESSION_NOT_COLLECTING);
        }
        session.markReady();
        eventPublisher.publishEvent(new SessionReadyEvent(sessionId));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionReady(SessionReadyEvent event) {
        requestAnalysis(event.sessionId());
    }

    // ANALYZING 을 먼저 커밋한 뒤 호출한다 (AI 콜백이 먼저 와도 상태가 맞도록)
    public void requestAnalysis(Long sessionId) {
        AiAnalyzeRequest request = transactionTemplate.execute(status -> startAnalysis(sessionId));
        if (request == null) {
            return;
        }
        try {
            aiClient.requestAnalysis(request);
        } catch (RestClientException e) {
            log.warn("[ai analysis] session {} 분석 요청 실패: {}", sessionId, e.getMessage());
            transactionTemplate.executeWithoutResult(status -> sessionRepository.findById(sessionId)
                    .ifPresent(BrowsingSession::failAnalysis));
        }
    }

    // READY 가 아니면(이미 요청됨 등) 건너뛴다. 원본이 없으면 분석할 수 없으므로 FAILED
    private AiAnalyzeRequest startAnalysis(Long sessionId) {
        BrowsingSession session = sessionRepository.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != SessionStatus.READY) {
            log.info("[ai analysis] session {} 는 READY 가 아니라 분석 요청을 건너뜀", sessionId);
            return null;
        }
        SessionRawPayload rawPayload = rawPayloadRepository.findById(sessionId).orElse(null);
        if (rawPayload == null) {
            log.warn("[ai analysis] session {} 원본이 없어 FAILED", sessionId);
            session.failAnalysis();
            return null;
        }

        session.startAnalysis();
        Long userId = session.getUserId();
        return new AiAnalyzeRequest(
                sessionId,
                rawPayload.getPayload(),
                history(userId, sessionId),
                model(ClusterModelScope.USER, userId),
                model(ClusterModelScope.GLOBAL, null));
    }

    // 같은 사용자의 이전 세션 유닛 피처 (AI 가 개인 정규화 · 개인 군집 재학습에 쓴다)
    private List<Map<String, Object>> history(Long userId, Long sessionId) {
        return resultRepository.findByUserId(userId).stream()
                .filter(result -> !result.getSessionId().equals(sessionId))
                .flatMap(result -> result.getUnitFeatures().stream())
                .toList();
    }

    private Map<String, Object> model(ClusterModelScope scope, Long userId) {
        var model = scope == ClusterModelScope.GLOBAL
                ? clusterModelRepository.findFirstByScope(scope)
                : clusterModelRepository.findByScopeAndUserId(scope, userId);
        return model.map(AiClusterModel::getModel).orElse(null);
    }
}
