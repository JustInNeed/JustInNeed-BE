package com.justinneed.community.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record SharedSessionView(
        Long id, List<String> tags, List<String> urls,
        String title, String markdown, List<String> insights, List<Source> sources
) {
    public static SharedSessionView from(BrowsingSession session, boolean includeContent) {
        return new SharedSessionView(
                session.getId(), session.getTags(),
                session.getSources().stream().map(Source::url).distinct().toList(),
                includeContent ? session.getTitle() : null,
                includeContent && session.getSummary() != null ? session.getSummary().getMarkdown() : null,
                includeContent && session.getSummary() != null ? session.getSummary().getInsights() : null,
                includeContent ? session.getSources() : null
        );
    }
}
