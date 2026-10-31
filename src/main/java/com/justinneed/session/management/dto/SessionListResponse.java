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

    // 다른 사용자가 보는 공개 세션. 규칙은 SessionDetailResponse.forViewer 와 같다
    public static SessionListResponse forViewer(BrowsingSession session, List<String> tags, boolean includeContent) {
        return new SessionListResponse(
                session.getId(),
                includeContent ? session.getTitle() : "",
                session.getStartedAt(),
                session.getEndedAt(),
                session.getPageCount(),
                session.getStatus(),
                false,
                session.isPublicSession(),
                tags
        );
    }
}
