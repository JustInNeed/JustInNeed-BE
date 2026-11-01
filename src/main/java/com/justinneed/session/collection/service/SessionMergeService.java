package com.justinneed.session.collection.service;

import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.service.AnalysisFailures;
import com.justinneed.session.analysis.service.AnalysisRequestService;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.collection.ChunkMergeException;
import com.justinneed.session.collection.SessionChunkMerger;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 청크 합치기.
 *
 * <pre>
 * 종료 요청에 청크가 다 왔을 때   커밋 뒤 비동기로 바로 실행
 * 나머지 (누락 대기 · 자동 종료)  복구 스케줄러가 실행
 * 청크 있음                      합친 원본 저장 + READY 를 한 트랜잭션으로 → 커밋 뒤 AI 호출
 * 청크 0개                       분석할 내용이 없어 세션 삭제 (행은 남겨 늦은 청크가 세션을 다시 만들지 않게 한다)
 * 원본 형식이 깨짐                롤백해 청크를 남기고 횟수를 센다. 정해진 횟수가 차면 FAILED
 * </pre>
 *
 * 세션 행을 잠그고 COLLECTING 인지 다시 보므로 스케줄러와 종료 요청이 겹쳐도 한 번만 합친다.
 */
@Service
public class SessionMergeService {

    private static final Logger log = LoggerFactory.getLogger(SessionMergeService.class);

    private final BrowsingSessionRepository sessionRepository;
    private final SessionCollectionRepository collectionRepository;
    private final SessionRawChunkRepository chunkRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;
    private final AnalysisRequestService analysisRequestService;
    private final AnalysisFailures failures;
    private final CommunityCardSync cards;
    private final CollectionSettings settings;
    private final TransactionTemplate transactionTemplate;

    public SessionMergeService(BrowsingSessionRepository sessionRepository,
            SessionCollectionRepository collectionRepository,
            SessionRawChunkRepository chunkRepository,
            SessionRawPayloadRepository rawPayloadRepository,
            AnalysisRequestService analysisRequestService,
            AnalysisFailures failures,
            CommunityCardSync cards,
            CollectionSettings settings,
            PlatformTransactionManager transactionManager) {
        this.sessionRepository = sessionRepository;
        this.collectionRepository = collectionRepository;
        this.chunkRepository = chunkRepository;
        this.rawPayloadRepository = rawPayloadRepository;
        this.analysisRequestService = analysisRequestService;
        this.failures = failures;
        this.cards = cards;
        this.settings = settings;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCollectionEnded(SessionCollectionEndedEvent event) {
        merge(event.sessionId(), LocalDateTime.now());
    }

    // 합칠 때가 됐으면 합친다. 스케줄러가 고른 뒤 청크가 새로 왔을 수 있어 잠금 안에서 다시 판단한다
    public void merge(Long sessionId, LocalDateTime now) {
        try {
            transactionTemplate.executeWithoutResult(status -> mergeLocked(sessionId, now));
        } catch (ChunkMergeException e) {
            transactionTemplate.executeWithoutResult(status -> recordFailure(sessionId, e));
        }
    }

    private void mergeLocked(Long sessionId, LocalDateTime now) {
        BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (!isCollecting(session)) {
            return;
        }
        SessionCollection collection = collectionRepository.getOrCreate(sessionId);
        Long userId = session.getUserId();
        String collectorSessionId = session.getCollectorSessionId();
        long received = chunkRepository.countByUserIdAndCollectorSessionId(userId, collectorSessionId);
        if (!collection.isMergeDue(received, session.getStartedAt(), settings.missingChunkWait(),
                settings.autoEndAfter(), now)) {
            return;
        }

        if (received == 0) {
            log.info("[chunk merge] session {} 은 청크가 없어 삭제", sessionId);
            session.delete();
            cards.sync(session);
            return;
        }
        if (collection.isEndRequested() && received < collection.getTotalChunks()) {
            log.warn("[chunk merge] session {} 청크 누락: 총 {}개 중 {}개만 받아 합침",
                    sessionId, collection.getTotalChunks(), received);
        }

        List<Map<String, Object>> payloads = chunkRepository.findPayloads(userId, collectorSessionId);
        Map<String, Object> merged = SessionChunkMerger.merge(payloads, new SessionChunkMerger.Context(
                collectorSessionId, session.getStartedAt(), collection.getRequestedEndedAt(), collection.getStopReason()));
        rawPayloadRepository.save(new SessionRawPayload(sessionId, userId, merged, now));
        analysisRequestService.finishCollection(sessionId);
    }

    private void recordFailure(Long sessionId, ChunkMergeException e) {
        BrowsingSession session = sessionRepository.lockById(sessionId).orElse(null);
        if (!isCollecting(session)) {
            return;
        }
        int attempts = collectionRepository.getOrCreate(sessionId).increaseMergeAttempts();
        log.warn("[chunk merge] session {} 합치기 실패 {}/{}회: {}",
                sessionId, attempts, settings.mergeMaxAttempts(), e.getMessage());
        if (attempts >= settings.mergeMaxAttempts()) {
            failures.fail(session);
        }
    }

    private static boolean isCollecting(BrowsingSession session) {
        return session != null && !session.isDeleted() && session.getStatus() == SessionStatus.COLLECTING;
    }
}
