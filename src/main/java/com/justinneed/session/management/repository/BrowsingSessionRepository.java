package com.justinneed.session.management.repository;

import com.justinneed.session.management.domain.BrowsingSession;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BrowsingSessionRepository extends JpaRepository<BrowsingSession, Long> {

    List<BrowsingSession> findByUserIdAndDeletedAtIsNullOrderByEndedAtDescStartedAtDesc(Long userId);

    // 세션 목록 한 페이지. favoriteOnly 면 즐겨찾기만 (REC-10-3)
    @Query("""
            select session
            from BrowsingSession session
            where session.userId = :userId
              and session.deletedAt is null
              and (:favoriteOnly = false or session.favorite = true)
            order by session.endedAt desc,
                     session.startedAt desc
            """)
    Slice<BrowsingSession> findListPage(
            @Param("userId") Long userId,
            @Param("favoriteOnly") boolean favoriteOnly,
            Pageable pageable
    );

    long countByUserIdAndDeletedAtIsNull(Long userId);

    long countByUserIdAndFavoriteTrueAndDeletedAtIsNull(Long userId);

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
