package com.justinneed.auth.repository;

import com.justinneed.auth.domain.RefreshToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // 토큰 행을 지운다. 지운 행 수(1 또는 0)로 재발급 성공 여부를 판단 (동시에 두 번 재발급해도 한 번만 1)
    @Modifying
    @Query("delete from RefreshToken r where r.tokenHash = :tokenHash")
    int deleteByHash(@Param("tokenHash") String tokenHash);

    void deleteByMemberId(Long memberId);

    // 만료된 토큰 행 정리용 (RefreshTokenCleanupScheduler)
    long deleteByExpiresAtBefore(LocalDateTime threshold);
}
