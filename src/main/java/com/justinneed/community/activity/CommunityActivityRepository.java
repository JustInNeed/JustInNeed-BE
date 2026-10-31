package com.justinneed.community.activity;

import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunitySessionRepository;
import java.time.*;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityActivityRepository extends JpaRepository<CommunityActivity, Long> {
    // 기간 안에 활동이 있었던 해시태그의 주인
    String TAG_OWNERS_BETWEEN = """
            (select h.ownerId from CommunityActivity a, CommunityHashtag h
            where h.id = a.tagId and a.occurredAt >= :start and a.occurredAt <= :end)
            """;

    boolean existsByActorIdAndTargetTypeAndTargetIdAndTagIdAndKindAndActivityDay(
            Long actorId, TargetType type, Long targetId, Long tagId, ActivityKind kind, LocalDate day);
    // 양 끝 포함. 탈퇴한 사람의 행은 남기고 읽을 때만 뺀다. 태그 주인이 탈퇴하면 그 태그도 빠진다
    @Query("""
            select new com.justinneed.community.activity.TrendingActivity(a.actorId, a.activityDay, a.kind, a.occurredAt,
                a.targetType, a.targetId, a.tagId, h.ownerId, h.name, n.sessionId)
            from CommunityActivity a
            join Member actor on actor.id = a.actorId and actor.withdrawnAt is null
            join CommunityHashtag h on h.id = a.tagId
            join Member owner on owner.id = h.ownerId and owner.withdrawnAt is null
            left join CommunityNode n on n.id = a.targetId
                and a.targetType = com.justinneed.community.bookmark.TargetType.NODE
            where a.occurredAt >= :start and a.occurredAt <= :end
            """)
    List<TrendingActivity> findTrending(@Param("start") Instant start, @Param("end") Instant end);

    @Query("""
            select new com.justinneed.community.activity.TrendingSession(s.id, s.userId, s.tags)
            from BrowsingSession s where s.userId in
            """ + TAG_OWNERS_BETWEEN + " and " + CommunitySessionRepository.PUBLIC_SESSION)
    List<TrendingSession> findTrendingSessions(@Param("start") Instant start, @Param("end") Instant end);

    @Query("select p from CommunityProfile p where p.userId in " + TAG_OWNERS_BETWEEN)
    List<CommunityProfile> findTrendingOwners(@Param("start") Instant start, @Param("end") Instant end);
}
