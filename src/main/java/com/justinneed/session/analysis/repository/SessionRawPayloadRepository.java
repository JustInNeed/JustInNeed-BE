package com.justinneed.session.analysis.repository;

import com.justinneed.session.analysis.domain.SessionRawPayload;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRawPayloadRepository extends JpaRepository<SessionRawPayload, Long> {

    @Modifying(clearAutomatically = true)
    @Query("delete from SessionRawPayload payload where payload.expiresAt < :now")
    int deleteExpired(@Param("now") LocalDateTime now);
}
