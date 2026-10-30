package com.justinneed.auth.service;

import com.justinneed.auth.repository.RevokedAccessTokenRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * revoked_access_tokens 테이블에서 토큰 자체가 만료된 행을 주기적으로 삭제한다.
 * (만료된 access 토큰은 JWT 검증 단계에서 이미 거부되므로 보안 목적이 아니라 테이블 비대화 방지 목적)
 */
@Component
public class RevokedAccessTokenCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RevokedAccessTokenCleanupScheduler.class);

    private final RevokedAccessTokenRepository revokedAccessTokenRepository;

    public RevokedAccessTokenCleanupScheduler(RevokedAccessTokenRepository revokedAccessTokenRepository) {
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
    }

    // 매시 30분 (access 토큰 유효시간이 1시간이라 행이 오래 남지 않는다)
    @Scheduled(cron = "0 30 * * * *")
    @Transactional
    public void purgeExpiredTokens() {
        int deleted = revokedAccessTokenRepository.deleteExpired(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[revoked access token cleanup] 만료 토큰 {}건 삭제", deleted);
        }
    }
}
