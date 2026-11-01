package com.justinneed.session.collection.dto;

import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;

// created: 이번 요청으로 세션을 새로 만들었으면 true
public record CollectorSessionResponse(
        Long sessionId,
        String collectorSessionId,
        String title,
        SessionStatus status,
        LocalDateTime startedAt,
        boolean created
) {

    public static CollectorSessionResponse of(BrowsingSession session, boolean created) {
        return new CollectorSessionResponse(session.getId(), session.getCollectorSessionId(), session.getTitle(),
                session.getStatus(), session.getStartedAt(), created);
    }
}
