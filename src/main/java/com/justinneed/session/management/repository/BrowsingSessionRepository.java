package com.justinneed.session.management.repository;

import com.justinneed.session.management.domain.BrowsingSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrowsingSessionRepository extends JpaRepository<BrowsingSession, Long> {

    List<BrowsingSession> findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(Long userId);

    @EntityGraph(attributePaths = "summary")
    @Query("""
            select session
            from BrowsingSession session
            where session.id = :id
              and session.userId = :userId
              and session.deletedAt is null
            """)
    Optional<BrowsingSession> findWithSummaryByIdAndUserIdAndDeletedAtIsNull(
            @Param("id") Long id,
            @Param("userId") Long userId
    );

    Optional<BrowsingSession> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);
}
