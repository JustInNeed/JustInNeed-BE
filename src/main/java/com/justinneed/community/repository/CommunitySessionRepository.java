package com.justinneed.community.repository;

import com.justinneed.session.management.domain.BrowsingSession;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CommunitySessionRepository extends Repository<BrowsingSession, Long> {
    @EntityGraph(attributePaths = "summary")
    @Query("""
            select s from BrowsingSession s
            where s.userId = :userId and s.deletedAt is null and s.publicSession = true
            order by s.id desc
            """)
    List<BrowsingSession> findPublicSessions(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from BrowsingSession s
            where s.id = :id and s.userId = :userId and s.deletedAt is null
            """)
    Optional<BrowsingSession> lockOwnedSession(@Param("userId") Long userId, @Param("id") Long id);
}
