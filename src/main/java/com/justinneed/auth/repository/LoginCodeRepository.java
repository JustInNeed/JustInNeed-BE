package com.justinneed.auth.repository;

import com.justinneed.auth.domain.LoginCode;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LoginCodeRepository extends JpaRepository<LoginCode, String> {

    // 아직 만료되지 않은 코드만 지운다. 지운 행 수(1 또는 0)로 교환 성공 여부를 판단 (동시에 두 번 교환해도 한 번만 1)
    @Modifying
    @Query("delete from LoginCode c where c.codeHash = :codeHash and c.expiresAt > :now")
    int deleteUsable(@Param("codeHash") String codeHash, @Param("now") LocalDateTime now);

    // 만료된 코드 행 정리용 (LoginCodeCleanupScheduler)
    @Modifying
    @Query("delete from LoginCode c where c.expiresAt <= :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
