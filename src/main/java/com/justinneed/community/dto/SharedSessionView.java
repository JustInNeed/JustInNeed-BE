package com.justinneed.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharedSessionView(
        Long id, List<String> tags, List<String> urls,
        LocalDateTime startedAt, LocalDateTime endedAt, int pageCount, SessionStatus status,
        String title, String markdown, List<Source> sources
) {
    public static SharedSessionView from(BrowsingSession session, List<String> tags, boolean includeContent) {
        return new SharedSessionView(
                session.getId(), tags,
                session.getSources().stream().map(Source::url).distinct().toList(),
                session.getStartedAt(), session.getEndedAt(), session.getPageCount(), session.getStatus(),
                includeContent ? session.getTitle() : null,
                includeContent && session.getSummary() != null ? session.getSummary().getMarkdown() : null,
                includeContent ? session.getSources() : null
        );
    }
}
