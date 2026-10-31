package com.justinneed.community.repository;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.dto.SessionTagsRow;
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
            where s.userId = :userId and
            """ + PUBLIC_SESSION + """
            order by s.id desc
            """)
    List<BrowsingSession> findPublicSessions(@Param("userId") Long userId);

    @Query("""
            select s.id from BrowsingSession s, Member m
            where m.id = s.userId and m.withdrawnAt is null and s.id in :ids and
            """ + PUBLIC_SESSION)
    List<Long> findPublicIds(@Param("ids") Collection<Long> ids);

    // 아래 셋은 CommunityCardBackfill 용
    @Query("""
            select s.id from BrowsingSession s, Member m
            where m.id = s.userId and m.withdrawnAt is null and
            """ + PUBLIC_SESSION + """
            and not exists (select c.sessionId from CommunityCardRow c where c.sessionId = s.id)
            order by s.id
            """)
    List<Long> findPublicIdsWithoutCard();

    @Query("""
            select new com.justinneed.community.dto.SessionTagsRow(s.userId, s.tags)
            from BrowsingSession s, Member m
            where m.id = s.userId and m.withdrawnAt is null and s.deletedAt is null
            """)
    List<SessionTagsRow> findLiveSessionTags();

    @Query("""
            select p from CommunityProfile p, Member m
            where m.id = p.userId and m.withdrawnAt is null
            """)
    List<CommunityProfile> findLiveProfiles();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from BrowsingSession s
            where s.id = :id and s.userId = :userId and s.deletedAt is null
            """)
    Optional<BrowsingSession> lockOwnedSession(@Param("userId") Long userId, @Param("id") Long id);
}
