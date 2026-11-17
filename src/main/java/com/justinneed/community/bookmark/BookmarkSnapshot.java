package com.justinneed.community.bookmark;

import com.justinneed.community.domain.NodeVisibility;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record BookmarkSnapshot(String title, List<Tag> hashtags, List<Node> nodes, List<Edge> edges) {
    public BookmarkSnapshot {
        hashtags = List.copyOf(hashtags);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
    public record Tag(Long hashtagId, Long ownerId, String name) { }
    @Schema(name = "BookmarkSnapshotNode")
    public record Node(Long nodeId, String label, String sourceUrl, String content, NodeVisibility visibilityScope) { }
    @Schema(name = "BookmarkSnapshotEdge")
    public record Edge(String from, String to) { }
}
