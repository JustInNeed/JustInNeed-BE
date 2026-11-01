package com.justinneed.session.collection.service;

import com.justinneed.session.analysis.service.AnalysisRequestService;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 시간이 지나야 진행되는 일과 서버가 죽어 끊긴 일을 이어서 처리한다. 상태는 모두 DB 에 있어 재시작해도 그대로 이어진다.
 *
 * <pre>
 * COLLECTING  누락 대기가 끝난 종료 요청, 자동 종료 시간이 지난 세션을 합친다 (합치지 못한 세션 복구 포함)
 * READY       AI 호출이 안 나간 세션을 다시 요청한다
 * ANALYZING   GET /input 이 없으면 재전송 규칙대로 처리하고, GET 뒤 콜백이 없으면 FAILED
 * </pre>
 *
 * 고른 뒤 상태가 바뀔 수 있어 처리하는 쪽에서 세션 행을 잠그고 다시 판단한다. 세션 하나가 실패해도 나머지는 계속한다.
 */
@Service
public class CollectionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(CollectionRecoveryService.class);

    private final SessionCollectionRepository collectionRepository;
    private final BrowsingSessionRepository sessionRepository;
    private final SessionMergeService mergeService;
    private final AnalysisRequestService analysisRequestService;
    private final CollectionSettings settings;

    public CollectionRecoveryService(SessionCollectionRepository collectionRepository,
            BrowsingSessionRepository sessionRepository,
            SessionMergeService mergeService,
            AnalysisRequestService analysisRequestService,
            CollectionSettings settings) {
        this.collectionRepository = collectionRepository;
        this.sessionRepository = sessionRepository;
        this.mergeService = mergeService;
        this.analysisRequestService = analysisRequestService;
        this.settings = settings;
    }

    public void run(LocalDateTime now) {
        forEach(collectionRepository.findMergeDue(now.minus(settings.missingChunkWait()),
                now.minus(settings.autoEndAfter())), id -> mergeService.merge(id, now));
        forEach(sessionRepository.findIdsByStatus(SessionStatus.READY), analysisRequestService::requestAnalysis);
        forEach(collectionRepository.findAwaitingInput(), id -> analysisRequestService.resendIfDue(id, now));
        forEach(collectionRepository.findCallbackOverdue(now.minus(settings.callbackTimeout())),
                id -> analysisRequestService.failIfCallbackOverdue(id, now));
    }

    private static void forEach(List<Long> sessionIds, Consumer<Long> action) {
        for (Long sessionId : sessionIds) {
            try {
                action.accept(sessionId);
            } catch (RuntimeException e) {
                log.error("[collection recovery] session {} 처리 실패", sessionId, e);
            }
        }
    }
}
