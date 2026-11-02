package com.justinneed.session.chunk.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 확장 청크 세션 정리 (ChunkSessionService 머리 주석 "안 끝난 세션").
 *   5분마다: 마지막 청크 뒤 1시간 조용한 COLLECTING 세션을 끝낸다 (세션마다 따로 — 하나가 실패해도 나머지는 진행)
 *   매일 04:40: 받은 지 21일 지난 청크를 지운다
 */
@Component
public class ChunkSessionTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChunkSessionTimeoutScheduler.class);

    private final BrowsingSessionRepository sessionRepository;
    private final SessionRawChunkRepository chunkRepository;
    private final ChunkSessionService chunkSessionService;

    public ChunkSessionTimeoutScheduler(BrowsingSessionRepository sessionRepository,
            SessionRawChunkRepository chunkRepository, ChunkSessionService chunkSessionService) {
        this.sessionRepository = sessionRepository;
        this.chunkRepository = chunkRepository;
        this.chunkSessionService = chunkSessionService;
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void closeQuietSessions() {
        closeQuietSessions(LocalDateTime.now());
    }

    // 끝낸 세션 수
    public int closeQuietSessions(LocalDateTime now) {
        int closed = 0;
        for (var session : sessionRepository.findByStatusAndCollectorSessionIdIsNotNull(SessionStatus.COLLECTING)) {
            try {
                if (chunkSessionService.closeIfQuiet(session.getId(), now) != null) {
                    closed++;
                }
            } catch (RuntimeException e) {
                log.warn("[chunk] 세션 {} 정리 실패: {}", session.getId(), e.getMessage(), e);
            }
        }
        return closed;
    }

    // 매일 04:40 (KST)
    @Scheduled(cron = "0 40 4 * * *", zone = JustInNeedApplication.TIME_ZONE)
    @Transactional
    public void purgeOldChunks() {
        int deleted = chunkRepository.deleteReceivedBefore(LocalDateTime.now().minus(ChunkSessionService.CHUNK_RETENTION));
        if (deleted > 0) {
            log.info("[chunk] 받은 지 {} 지난 청크 {}건 삭제", ChunkSessionService.CHUNK_RETENTION, deleted);
        }
    }
}
