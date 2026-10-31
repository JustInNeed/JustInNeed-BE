package com.justinneed.community.reaction;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.catalog.CommunityHashtagRepository;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.graph.*;
import java.util.*;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class TargetResolver {
    private final CommunityNodeRepository nodes;
    private final CommunityHashtagRepository tags;
    private final PublicGraphService graphs;
    private final CommunityTags communityTags;
    public TargetResolver(CommunityNodeRepository nodes, CommunityHashtagRepository tags, PublicGraphService graphs,
            CommunityTags communityTags) {
        this.nodes = nodes; this.tags = tags; this.graphs = graphs; this.communityTags = communityTags;
    }
    public Long owner(TargetType type, Long id) {
        return type == TargetType.NODE
                ? nodes.findById(id).orElseThrow(CommunityErrors::missing).getOwnerId()
                : tags.findById(id).orElseThrow(CommunityErrors::missing).getOwnerId();
    }
    public Resolved resolve(TargetType type, Long id) {
        var owner = owner(type, id);
        var graph = graphs.graph(owner, null);
        // 내용까지 공개한 작성자의 그래프는 AI 노드라, 전에 URL 노드를 골라 둔 북마크 · 나도 탐색중은 URL 그래프에서 찾는다
        if (type == TargetType.NODE && !hasNode(graph, id)) graph = graphs.urlGraph(owner);
        if (type == TargetType.NODE && !hasNode(graph, id)
                || type == TargetType.HASHTAG && graph.hashtags().stream().noneMatch(t -> t.hashtagId().equals(id))) {
            throw CommunityErrors.missing();
        }
        // AI 노드는 태그가 뿌리에만 이어져 있어, 연결선을 따라 올라가거나(노드) 내려가서(태그) 찾는다
        List<BookmarkSnapshot.Edge> edges;
        Set<String> tagKeys;
        Set<String> nodeKeys;
        if (type == TargetType.NODE) {
            String node = "node:" + id;
            tagKeys = reachable(graph.edges(), node, BookmarkSnapshot.Edge::to, BookmarkSnapshot.Edge::from);
            nodeKeys = Set.of(node);
            edges = tagKeys.stream().filter(k -> k.startsWith("tag:")).map(k -> new BookmarkSnapshot.Edge(k, node)).toList();
        } else {
            String tag = "tag:" + id;
            nodeKeys = reachable(graph.edges(), tag, BookmarkSnapshot.Edge::from, BookmarkSnapshot.Edge::to);
            tagKeys = Set.of(tag);
            edges = graph.edges().stream().filter(e -> nodeKeys.contains(e.from())).toList();
        }
        return new Resolved(graph.ownerId(),
                graph.hashtags().stream().filter(t -> tagKeys.contains("tag:" + t.hashtagId())).toList(),
                graph.nodes().stream().filter(n -> nodeKeys.contains("node:" + n.nodeId())).toList(), edges);
    }
    /** 카드의 공개 해시태그 행. 카드 응답의 태그가 이미 비공개 태그를 뺀 것이다. 행이 없는 태그는 그래프처럼 건너뛴다. */
    public List<BookmarkSnapshot.Tag> cardTags(CommunityCard card) {
        Long ownerId = card.owner().userId();
        var index = communityTags.index(ownerId);
        return card.hashtags().stream().map(CommunityTags::key).distinct().map(index::get).filter(Objects::nonNull)
                .map(tag -> new BookmarkSnapshot.Tag(tag.getId(), ownerId, tag.getName())).toList();
    }
    /** 카드 한 장의 그래프: 공개 범위를 따르는 작성자 그래프에서 그 세션의 노드 · 연결선만 고른다. AI 결과가 없는 세션은 URL 노드를 쓴다. */
    public Resolved card(CommunityCard card) {
        Long ownerId = card.owner().userId();
        var ids = new HashSet<Long>();
        nodes.findBySessionId(card.mindmapId()).forEach(node -> ids.add(node.getId()));
        var graph = graphs.graph(ownerId, null);
        if (graph.nodes().stream().noneMatch(n -> ids.contains(n.nodeId()))) graph = graphs.urlGraph(ownerId);
        var cardNodes = graph.nodes().stream().filter(n -> ids.contains(n.nodeId())).toList();
        var cardTags = cardTags(card);
        var keys = new HashSet<String>();
        cardNodes.forEach(n -> keys.add("node:" + n.nodeId()));
        var from = new HashSet<>(keys);
        cardTags.forEach(t -> from.add("tag:" + t.hashtagId()));
        return new Resolved(ownerId, cardTags, cardNodes,
                graph.edges().stream().filter(e -> keys.contains(e.to()) && from.contains(e.from())).toList());
    }
    private static boolean hasNode(PublicGraphService.Graph graph, Long id) {
        return graph.nodes().stream().anyMatch(n -> n.nodeId().equals(id));
    }
    // start 와, start 에서 from → to 로 닿는 키 전부
    private static Set<String> reachable(List<BookmarkSnapshot.Edge> edges, String start,
            Function<BookmarkSnapshot.Edge, String> from, Function<BookmarkSnapshot.Edge, String> to) {
        var seen = new LinkedHashSet<>(List.of(start));
        var queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (var edge : edges) {
                if (from.apply(edge).equals(current) && seen.add(to.apply(edge))) queue.add(to.apply(edge));
            }
        }
        return seen;
    }
    public record Resolved(Long ownerId, List<BookmarkSnapshot.Tag> tags,
            List<BookmarkSnapshot.Node> nodes, List<BookmarkSnapshot.Edge> edges) { }
}
