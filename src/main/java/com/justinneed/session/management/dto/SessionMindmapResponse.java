package com.justinneed.session.management.dto;

import com.justinneed.community.api.HttpUrl;
import java.time.LocalDateTime;
import java.util.List;

public record SessionMindmapResponse(
        List<Node> nodes,
        List<Edge> edges
) {

    public static final SessionMindmapResponse EMPTY = new SessionMindmapResponse(List.of(), List.of());

    /** 다른 사용자에게 줄 응답. 화면이 인용 링크를 href 로 그대로 쓰므로 http(s) 가 아닌 인용은 뺀다. */
    public SessionMindmapResponse withWebCitationsOnly() {
        return new SessionMindmapResponse(nodes.stream().map(Node::withWebCitationOnly).toList(), edges);
    }

    public record Node(
            String id,
            String type,
            String keyword,
            Double score,
            int childCount,
            Citation citation
    ) {
        private Node withWebCitationOnly() {
            return citation == null || HttpUrl.isValid(citation.url()) ? this
                    : new Node(id, type, keyword, score, childCount, null);
        }
    }

    // 유닛 노드의 원문 인용
    public record Citation(
            String title,
            String url,
            String excerpt,
            LocalDateTime visitedAt
    ) {
    }

    public record Edge(
            String from,
            String to,
            String type,
            boolean primary
    ) {
    }
}
