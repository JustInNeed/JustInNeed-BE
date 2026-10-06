package com.justinneed.community.reaction;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.catalog.CommunityHashtagRepository;
import com.justinneed.community.graph.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TargetResolver {
    private final CommunityNodeRepository nodes;
    private final CommunityHashtagRepository tags;
    private final PublicGraphService graphs;
    public TargetResolver(CommunityNodeRepository nodes, CommunityHashtagRepository tags, PublicGraphService graphs) {
        this.nodes = nodes; this.tags = tags; this.graphs = graphs;
    }
    public Long owner(TargetType type, Long id) {
        return type == TargetType.NODE
                ? nodes.findById(id).orElseThrow(CommunityErrors::missing).getOwnerId()
                : tags.findById(id).orElseThrow(CommunityErrors::missing).getOwnerId();
    }
    public Resolved resolve(TargetType type, Long id) {
        var graph = graphs.graph(owner(type, id), null);
        if (type == TargetType.NODE && graph.nodes().stream().noneMatch(n -> n.nodeId().equals(id))
                || type == TargetType.HASHTAG && graph.hashtags().stream().noneMatch(t -> t.hashtagId().equals(id))) {
            throw CommunityErrors.missing();
        }
        var edges = graph.edges().stream().filter(e -> type == TargetType.NODE
                ? e.to().equals("node:" + id) : e.from().equals("tag:" + id)).toList();
        var tagKeys = new HashSet<String>();
        var nodeKeys = new HashSet<String>();
        edges.forEach(e -> { tagKeys.add(e.from()); nodeKeys.add(e.to()); });
        if (type == TargetType.HASHTAG) tagKeys.add("tag:" + id);
        if (type == TargetType.NODE) nodeKeys.add("node:" + id);
        return new Resolved(graph.ownerId(),
                graph.hashtags().stream().filter(t -> tagKeys.contains("tag:" + t.hashtagId())).toList(),
                graph.nodes().stream().filter(n -> nodeKeys.contains("node:" + n.nodeId())).toList(), edges);
    }
    public record Resolved(Long ownerId, List<BookmarkSnapshot.Tag> tags,
            List<BookmarkSnapshot.Node> nodes, List<BookmarkSnapshot.Edge> edges) { }
}
