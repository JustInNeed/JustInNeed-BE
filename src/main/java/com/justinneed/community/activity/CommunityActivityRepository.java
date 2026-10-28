package com.justinneed.community.activity;

import com.justinneed.community.bookmark.TargetType;
import java.time.*;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityActivityRepository extends JpaRepository<CommunityActivity, Long> {
    boolean existsByActorIdAndTargetTypeAndTargetIdAndTagIdAndKindAndActivityDay(
            Long actorId, TargetType type, Long targetId, Long tagId, ActivityKind kind, LocalDate day);
    List<CommunityActivity> findByOccurredAtGreaterThanEqualAndOccurredAtLessThanEqual(Instant start, Instant end);
}
