package com.justinneed.auth.service;

import com.justinneed.auth.repository.LoginCodeRepository;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * login_codes 테이블에 남은 만료 행(로그인했지만 FE 가 교환하지 않은 코드)을 주기적으로 삭제한다.
 * (만료 코드는 교환 단계에서 이미 거부되므로 보안 목적이 아니라 테이블 비대화 방지 목적)
 */
@Component
public class LoginCodeCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(LoginCodeCleanupScheduler.class);

    private final LoginCodeRepository loginCodeRepository;

    public LoginCodeCleanupScheduler(LoginCodeRepository loginCodeRepository) {
        this.loginCodeRepository = loginCodeRepository;
    }

    // 매시 정각 (코드 유효시간이 60초라 하루 한 번보다 자주 비운다)
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void purgeExpiredCodes() {
        int deleted = loginCodeRepository.deleteExpired(LocalDateTime.now());
        if (deleted > 0) {
            log.info("[login code cleanup] 만료 코드 {}건 삭제", deleted);
        }
    }
}
