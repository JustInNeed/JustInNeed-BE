package com.justinneed.session.management.dto;

import java.time.LocalDateTime;
import java.util.List;

public record SessionMindmapResponse(
        List<Node> nodes,
        List<Edge> edges
) {

    public static final SessionMindmapResponse EMPTY = new SessionMindmapResponse(List.of(), List.of());

    public record Node(
            String id,
            String type,
            String keyword,
            Double score,
            int childCount,
            Citation citation
    ) {
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
