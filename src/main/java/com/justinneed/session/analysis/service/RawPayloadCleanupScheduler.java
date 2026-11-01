package com.justinneed.session.analysis.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션 원본(session_raw_payloads)을 분석 완료 또는 실패 후 21일이 지나면 삭제한다. 실패 세션의 청크도 같은 기한으로 삭제한다.
 * 분석 화면은 AI 결과(session_results)로 집계하므로 원본은 분석 실패 재시도 · 피처 정의 변경 시 재계산 용도로만 보관한다.
 * 졸업 평가용 원본은 삭제 전에 따로 백업한다.
 */
@Component
public class RawPayloadCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RawPayloadCleanupScheduler.class);

    private final SessionRawPayloadRepository rawPayloadRepository;
    private final SessionRawChunkRepository chunkRepository;

    public RawPayloadCleanupScheduler(SessionRawPayloadRepository rawPayloadRepository,
            SessionRawChunkRepository chunkRepository) {
        this.rawPayloadRepository = rawPayloadRepository;
        this.chunkRepository = chunkRepository;
    }

    // 매일 04:30 (KST)
    @Scheduled(cron = "0 30 4 * * *", zone = JustInNeedApplication.TIME_ZONE)
    @Transactional
    public void purgeExpiredPayloads() {
        LocalDateTime now = LocalDateTime.now();
        int deletedChunks = chunkRepository.deleteFailedBefore(now.minusDays(SessionRawPayload.RETENTION_DAYS));
        if (deletedChunks > 0) {
            log.info("[raw payload cleanup] 보관 기한이 지난 실패 세션 청크 {}건 삭제", deletedChunks);
        }
        int deleted = rawPayloadRepository.deleteExpired(now);
        if (deleted > 0) {
            log.info("[raw payload cleanup] 보관 기한이 지난 세션 원본 {}건 삭제", deleted);
        }
    }
}
