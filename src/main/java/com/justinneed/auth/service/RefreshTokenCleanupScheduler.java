package com.justinneed.auth.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.auth.repository.RefreshTokenRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * refresh_tokens 테이블에 남은 만료 행을 주기적으로 삭제한다.
 * (만료 토큰은 JWT 검증 단계에서 이미 거부되므로 보안 목적이 아니라 테이블 비대화 방지 목적)
 */
@Component
public class RefreshTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupScheduler.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public RefreshTokenCleanupScheduler(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    // 매일 04:00 (KST)
    @Scheduled(cron = "0 0 4 * * *", zone = JustInNeedApplication.TIME_ZONE)
    @Transactional
    public void purgeExpiredTokens() {
        long deleted = refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[refresh token cleanup] 만료 토큰 {}건 삭제", deleted);
        }
    }
}
