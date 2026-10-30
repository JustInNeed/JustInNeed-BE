package com.justinneed.auth.repository;

import com.justinneed.auth.domain.RevokedAccessToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RevokedAccessTokenRepository extends JpaRepository<RevokedAccessToken, String> {

    // 이미 무효화한 토큰이면 아무것도 하지 않는다 (같은 토큰으로 동시에 로그아웃 · 탈퇴해도 PK 충돌 없음)
    @Modifying
    @Query(value = "insert into revoked_access_tokens (token_hash, expires_at) values (:tokenHash, :expiresAt)"
            + " on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("tokenHash") String tokenHash, @Param("expiresAt") LocalDateTime expiresAt);

    boolean existsByTokenHashAndExpiresAtAfter(String tokenHash, LocalDateTime now);

    // 만료된 행 정리용 (RevokedAccessTokenCleanupScheduler)
    @Modifying
    @Query("delete from RevokedAccessToken t where t.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
