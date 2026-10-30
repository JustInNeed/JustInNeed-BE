package com.justinneed.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 로그아웃 · 탈퇴로 만료 전에 무효화한 access 토큰. (TokenDenylist 가 사용)
 * 토큰 원문은 저장하지 않고 SHA-256 해시만 보관한다.
 * 토큰 자체 만료 시각(expires_at)이 지나면 JWT 검증에서 이미 거부되므로 RevokedAccessTokenCleanupScheduler 가 정리한다.
 */
@Entity
@Table(name = "revoked_access_tokens")
public class RevokedAccessToken {

    @Id
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected RevokedAccessToken() {
    }

    public RevokedAccessToken(String tokenHash, LocalDateTime expiresAt) {
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
