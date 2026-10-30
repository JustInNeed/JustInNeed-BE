package com.justinneed.auth.jwt;

import com.justinneed.auth.repository.RevokedAccessTokenRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로그아웃 · 탈퇴로 만료 전에 무효화한 access 토큰의 denylist.
 * DB 테이블(revoked_access_tokens)에 토큰 SHA-256 해시와 토큰 만료 시각을 저장하므로 배포 · 재시작 후에도 유지된다.
 * 만료 시각이 지난 행은 조회에서 무시하고, RevokedAccessTokenCleanupScheduler 가 주기적으로 지운다.
 */
@Component
public class TokenDenylist {

    private final RevokedAccessTokenRepository revokedAccessTokenRepository;

    public TokenDenylist(RevokedAccessTokenRepository revokedAccessTokenRepository) {
        this.revokedAccessTokenRepository = revokedAccessTokenRepository;
    }

    @Transactional
    public void deny(String token, long expiryEpochSecond) {
        LocalDateTime expiresAt = Instant.ofEpochSecond(expiryEpochSecond)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        revokedAccessTokenRepository.insertIfAbsent(TokenHashes.sha256(token), expiresAt);
    }

    public boolean isDenied(String token) {
        return revokedAccessTokenRepository.existsByTokenHashAndExpiresAtAfter(
                TokenHashes.sha256(token), LocalDateTime.now());
    }
}
