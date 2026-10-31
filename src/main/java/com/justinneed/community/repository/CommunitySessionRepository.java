package com.justinneed.community.repository;

import com.justinneed.community.dto.PublicSessionRow;
import com.justinneed.session.management.domain.BrowsingSession;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CommunitySessionRepository extends Repository<BrowsingSession, Long> {
    // 다른 사용자에게 보일 수 있는 세션
    String PUBLIC_SESSION = """
            s.deletedAt is null and s.publicSession = true
            and s.status = com.justinneed.session.management.domain.SessionStatus.COMPLETED
            """;

    @EntityGraph(attributePaths = "summary")
    @Query("""
            select s from BrowsingSession s
            where s.userId = :userId and s.deletedAt is null and s.publicSession = true
            order by s.id desc
            """)
    List<BrowsingSession> findPublicSessions(@Param("userId") Long userId);

    @Query("""
            select new com.justinneed.community.dto.PublicSessionRow(s.id, s.userId, s.tags)
            from BrowsingSession s, Member m
            where m.id = s.userId and m.withdrawnAt is null and s.id in :ids and
            """ + PUBLIC_SESSION)
    List<PublicSessionRow> findPublicRows(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from BrowsingSession s
            where s.id = :id and s.userId = :userId and s.deletedAt is null
            """)
    Optional<BrowsingSession> lockOwnedSession(@Param("userId") Long userId, @Param("id") Long id);
}
