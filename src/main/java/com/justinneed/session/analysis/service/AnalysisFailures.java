package com.justinneed.session.analysis.service;

import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

/**
 * 세션을 FAILED 로 바꾸는 단일 경로. 실패 시각과 원본 보관 기한을 함께 남긴다.
 * 청크는 실패 시각으로, 원본은 expires_at 으로 21일 뒤 지운다 (RawPayloadCleanupScheduler).
 * 세션 행을 잠근 트랜잭션 안에서 호출한다.
 */
@Component
public class AnalysisFailures {

    private final SessionCollectionRepository collectionRepository;
    private final SessionRawPayloadRepository rawPayloadRepository;

    public AnalysisFailures(SessionCollectionRepository collectionRepository,
            SessionRawPayloadRepository rawPayloadRepository) {
        this.collectionRepository = collectionRepository;
        this.rawPayloadRepository = rawPayloadRepository;
    }

    public void fail(BrowsingSession session) {
        LocalDateTime failedAt = LocalDateTime.now();
        session.failAnalysis();
        collectionRepository.getOrCreate(session.getId()).markFailed(failedAt);
        rawPayloadRepository.findById(session.getId()).ifPresent(rawPayload -> rawPayload.markFailed(failedAt));
    }
}
