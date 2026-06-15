package com.justinneed.session.management.dto;

import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.List;

public record SessionDetailResponse(
        Long id,
        String title,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        int pageCount,
        SessionStatus status,
        boolean isFavorite,
        boolean isPublic,
        List<String> tags,
        SummaryResponse summary,
        List<Source> sources
) {

    public static SessionDetailResponse from(BrowsingSession session) {
        return new SessionDetailResponse(
                session.getId(),
                session.getTitle(),
                session.getStartedAt(),
                session.getEndedAt(),
                session.getPageCount(),
                session.getStatus(),
                session.isFavorite(),
                session.isPublicSession(),
                session.getTags(),
                SummaryResponse.from(session.getSummary()),
                session.getSources()
        );
    }

    public record SummaryResponse(
            String heading,
            String markdown,
            List<String> insights
    ) {
        private static SummaryResponse from(Summary summary) {
            if (summary == null) {
                return null;
            }
            return new SummaryResponse(summary.getHeading(), summary.getMarkdown(), summary.getInsights());
        }
    }
}
