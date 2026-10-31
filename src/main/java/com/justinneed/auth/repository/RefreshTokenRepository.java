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

    // 재발급 · 로그아웃 때 묶음을 찾는다. 없는 토큰 · 묶음 없는 기존 토큰이면 null
    @Query("select r.groupId from RefreshToken r where r.tokenHash = :tokenHash")
    String findGroupIdByHash(@Param("tokenHash") String tokenHash);

    boolean existsByGroupId(String groupId);

    // 로그아웃 시 웹 · 확장 토큰을 함께 지운다
    @Modifying
    @Query("delete from RefreshToken r where r.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") String groupId);

    // 탈퇴 시 전부 삭제. 조회 후 삭제가 아니라 한 번에 지워 동시 재발급과 겹쳐도 충돌하지 않는다
    @Modifying
    @Query("delete from RefreshToken r where r.memberId = :memberId")
    void deleteByMemberId(@Param("memberId") Long memberId);

    // 만료된 토큰 행 정리용 (RefreshTokenCleanupScheduler)
    long deleteByExpiresAtBefore(LocalDateTime threshold);
}
