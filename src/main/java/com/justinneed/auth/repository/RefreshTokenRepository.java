package com.justinneed.auth.repository;

import com.justinneed.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    // 만료된 토큰 행 정리용 (RefreshTokenCleanupScheduler)
    long deleteByExpiresAtBefore(LocalDateTime threshold);
}
