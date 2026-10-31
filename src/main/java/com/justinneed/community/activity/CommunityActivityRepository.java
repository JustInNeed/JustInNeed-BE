package com.justinneed.community.activity;

import com.justinneed.community.bookmark.TargetType;
import java.time.*;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityActivityRepository extends JpaRepository<CommunityActivity, Long> {
    boolean existsByActorIdAndTargetTypeAndTargetIdAndTagIdAndKindAndActivityDay(
            Long actorId, TargetType type, Long targetId, Long tagId, ActivityKind kind, LocalDate day);
    List<CommunityActivity> findByOccurredAtGreaterThanEqualAndOccurredAtLessThanEqual(Instant start, Instant end);
    // 집계용: 양 끝 포함. 탈퇴 회원의 행은 남기고 읽을 때만 뺀다
    @Query("""
            select a from CommunityActivity a where a.occurredAt >= :start and a.occurredAt <= :end
            and exists (select m.id from Member m where m.id = a.actorId and m.withdrawnAt is null)
            """)
    List<CommunityActivity> findByActiveActorsBetween(@Param("start") Instant start, @Param("end") Instant end);
}
