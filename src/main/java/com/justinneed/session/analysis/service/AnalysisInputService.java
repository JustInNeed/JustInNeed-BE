package com.justinneed.session.analysis.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.domain.AiClusterModel;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.dto.AiAnalysisInput;
import com.justinneed.session.analysis.dto.AiSessionHistory;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 가 분석에 필요한 값을 가져가는 내부 API.
 *
 * <pre>
 * GET /input    AI 가 작업을 시작할 때 호출. 호출 시각을 남기고 콜백 대기를 여기서부터 잰다
 *               ANALYZING 이 아니거나 지운 세션 409, 없는 세션 404, 원본이 없으면 FAILED 로 바꾸고 404
 *               (4xx 면 AI 는 분석을 건너뛰고 콜백을 보내지 않는다)
 * GET /history  재학습이 필요할 때만 호출. 큰 값이라 이때만 읽는다
 * </pre>
 *
 * (FAILED 를 남겨야 하므로 CustomException 은 롤백하지 않는다.)
 */
@Service
public class AnalysisInputService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisInputService.class);

    private final BrowsingSessionRepository sessionRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;
    private final SessionResultRepository resultRepository;
    private final AiClusterModelRepository clusterModelRepository;
    private final SessionCollectionRepository collectionRepository;
    private final AnalysisFailures failures;

    public AnalysisInputService(BrowsingSessionRepository sessionRepository,
            SessionRawPayloadRepository rawPayloadRepository,
            SessionResultRepository resultRepository,
            AiClusterModelRepository clusterModelRepository,
            SessionCollectionRepository collectionRepository,
            AnalysisFailures failures) {
        this.sessionRepository = sessionRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.resultRepository = resultRepository;
        this.clusterModelRepository = clusterModelRepository;
        this.collectionRepository = collectionRepository;
        this.failures = failures;
    }

    @Transactional(noRollbackFor = CustomException.class)
    public AiAnalysisInput input(Long sessionId) {
        BrowsingSession session = sessionRepository.lockById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        if (session.isDeleted() || session.getStatus() != SessionStatus.ANALYZING) {
            throw new CustomException(ErrorCode.ANALYSIS_NOT_IN_PROGRESS);
        }
        SessionRawPayload rawPayload = rawPayloadRepository.findById(sessionId).orElse(null);
        if (rawPayload == null) {
            log.warn("[ai analysis] session {} 원본이 없어 FAILED", sessionId);
            failures.fail(session);
            throw new CustomException(ErrorCode.RAW_PAYLOAD_NOT_FOUND);
        }

        collectionRepository.getOrCreate(sessionId).markInputFetched(LocalDateTime.now());
        Long userId = session.getUserId();
        return new AiAnalysisInput(
                sessionId,
                userId,
                rawPayload.getPayload(),
                resultRepository.countActiveExcluding(userId, sessionId),
                model(ClusterModelScope.USER, userId),
                model(ClusterModelScope.GLOBAL, null));
    }

    // 같은 사용자의 이전 세션 유닛 피처 (AI 가 개인 군집 재학습에 쓴다). 현재 세션과 지운 세션은 뺀다
    @Transactional(readOnly = true)
    public AiSessionHistory history(Long sessionId) {
        BrowsingSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        List<Map<String, Object>> unitFeatures = resultRepository.findActiveByUserId(session.getUserId()).stream()
                .filter(result -> !result.getSessionId().equals(sessionId))
                .flatMap(result -> result.getUnitFeatures().stream())
                .toList();
        return new AiSessionHistory(unitFeatures);
    }

    private Map<String, Object> model(ClusterModelScope scope, Long userId) {
        var model = scope == ClusterModelScope.GLOBAL
                ? clusterModelRepository.findFirstByScope(scope)
                : clusterModelRepository.findByScopeAndUserId(scope, userId);
        return model.map(AiClusterModel::getModel).orElse(null);
    }
}
