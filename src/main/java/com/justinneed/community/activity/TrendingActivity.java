package com.justinneed.community.activity;

import com.justinneed.community.bookmark.TargetType;
import java.time.*;

// 집계에 필요한 활동 한 줄. sessionId 는 NODE 대상일 때 그 노드의 세션
public record TrendingActivity(Long actorId, LocalDate activityDay, ActivityKind kind, Instant occurredAt,
        TargetType targetType, Long targetId, Long tagId, Long ownerId, String tagName, Long sessionId) { }
