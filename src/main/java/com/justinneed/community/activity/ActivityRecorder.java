package com.justinneed.community.activity;

import com.justinneed.community.bookmark.*;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ActivityRecorder {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private final CommunityActivityRepository activities;
    private final Clock clock;
    public ActivityRecorder(CommunityActivityRepository activities, Clock clock) {
        this.activities = activities; this.clock = clock;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long actor, TargetType type, Long target, ActivityKind kind, List<BookmarkSnapshot.Tag> tags) {
        var now = clock.instant();
        var day = now.atZone(ZONE).toLocalDate();
        for (var tag : tags) {
            if (!activities.existsByActorIdAndTargetTypeAndTargetIdAndTagIdAndKindAndActivityDay(
                    actor, type, target, tag.hashtagId(), kind, day)) {
                activities.save(new CommunityActivity(actor, type, target, tag.hashtagId(), kind, now, day));
            }
        }
    }
}
