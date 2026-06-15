package com.justinneed.session.management.dto;

import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;

public record SessionListResponse(
        Long id,
        String title,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        int pageCount,
        SessionStatus status,
        boolean isFavorite,
        boolean isPublic,
        List<String> tags
) {

    public static SessionListResponse from(BrowsingSession session) {
        return new SessionListResponse(
                session.getId(),
                session.getTitle(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getPageCount(),
                session.getStatus(),
                session.isFavorite(),
                session.isPublicSession(),
                session.getTags()
        );
    }
}
