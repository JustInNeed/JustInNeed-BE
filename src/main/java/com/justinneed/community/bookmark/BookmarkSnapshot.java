package com.justinneed.community.bookmark;

import com.justinneed.community.domain.NodeVisibility;
import java.util.List;

public record BookmarkSnapshot(String title, List<Tag> hashtags, List<Node> nodes, List<Edge> edges) {
    public BookmarkSnapshot {
        hashtags = List.copyOf(hashtags);
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
    public record Tag(Long hashtagId, Long ownerId, String name) { }
    public record Node(Long nodeId, String label, String sourceUrl, String content, NodeVisibility visibilityScope) { }
    public record Edge(String from, String to) { }
}
