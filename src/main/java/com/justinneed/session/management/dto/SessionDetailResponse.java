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
        boolean isOwner,
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
                true,
                session.isFavorite(),
                session.isPublicSession(),
                session.getTags(),
                SummaryResponse.from(session.getSummary()),
                session.getSources()
        );
    }

    // 다른 사용자가 보는 공개 세션. 즐겨찾기는 소유자만의 값이라 보내지 않는다.
    // 노드 공개 범위가 URL 만이면 커뮤니티 API(SharedSessionView)와 같게 제목 · 본문 · 출처 내용을 빼고 링크만 준다
    public static SessionDetailResponse forViewer(BrowsingSession session, List<String> tags, boolean includeContent) {
        return new SessionDetailResponse(
                session.getId(),
                includeContent ? session.getTitle() : "",
                session.getStartedAt(),
                session.getEndedAt(),
                session.getPageCount(),
                session.getStatus(),
                false,
                false,
                session.isPublicSession(),
                tags,
                includeContent ? SummaryResponse.from(session.getSummary()) : null,
                includeContent ? session.getSources() : linksOnly(session.getSources())
        );
    }

    private static List<Source> linksOnly(List<Source> sources) {
        return sources.stream().map(source -> new Source(null, source.url(), null)).toList();
    }

    public record SummaryResponse(
            String markdown
    ) {
        private static SummaryResponse from(Summary summary) {
            if (summary == null) {
                return null;
            }
            return new SummaryResponse(summary.getMarkdown());
        }
    }
}
