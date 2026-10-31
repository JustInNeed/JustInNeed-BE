package com.justinneed.community.graph;

import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.api.HttpUrl;
import com.justinneed.community.api.SelectionKey;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.community.card.SessionMindmaps;
import com.justinneed.community.catalog.*;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.follow.CommunityFollowRepository;
import com.justinneed.community.pin.SharedSessionService;
import com.justinneed.community.reaction.ProfileLikeRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionMindmapResponse;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PublicGraphService {
    private static final String HIERARCHY = "hierarchy";

    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;
    private final CommunityTags tags;
    private final CommunityNodeRepository nodes;
    private final SessionMindmaps mindmaps;
    private final MemberRepository members;
    private final ProfileLikeRepository likes;
    private final SharedSessionService pins;
    private final BookmarkQueryService bookmarks;
    private final CommunityCardReader cards;
    private final CommunityFollowRepository follows;

    public PublicGraphService(CommunityProfiles profiles, CommunitySharing sharing, CommunityTags tags,
            CommunityNodeRepository nodes, SessionMindmaps mindmaps, MemberRepository members,
            ProfileLikeRepository likes, SharedSessionService pins, BookmarkQueryService bookmarks,
            CommunityCardReader cards, CommunityFollowRepository follows) {
        this.profiles = profiles; this.sharing = sharing; this.tags = tags; this.nodes = nodes;
        this.mindmaps = mindmaps; this.members = members; this.likes = likes; this.pins = pins; this.bookmarks = bookmarks;
        this.cards = cards; this.follows = follows;
    }

    /** 작성자 공개 범위를 따른다: 내용까지는 AI 마인드맵(hierarchy), URL만은 태그 → URL. */
    public Graph graph(Long ownerId, Long hashtagId) {
        var profile = profiles.read(ownerId);
        return build(profile, hashtagId, profile.getVisibility() == NodeVisibility.CONTENT);
    }

    /** 공개 범위와 상관없이 태그 → URL. AI 노드 전에 저장한 URL 노드 id 를 찾을 때 쓴다. */
    public Graph urlGraph(Long ownerId) {
        return build(profiles.read(ownerId), null, false);
    }

    private Graph build(CommunityProfile profile, Long hashtagId, boolean aiNodes) {
        Long ownerId = profile.getUserId();
        var owner = members.findByIdAndWithdrawnAtIsNull(ownerId).orElseThrow(CommunityErrors::missing);
        var tagIndex = tags.index(ownerId);
        var indexedNodes = new HashMap<String, CommunityNode>();
        nodes.findByOwnerId(ownerId).forEach(n -> indexedNodes.put(n.getSessionId() + ":" + n.getSourceKey(), n));
        var visibleTags = new LinkedHashMap<Long, BookmarkSnapshot.Tag>();
        var leaves = new ArrayList<BookmarkSnapshot.Node>();
        var edges = new LinkedHashSet<BookmarkSnapshot.Edge>();
        var counts = new HashMap<Long, Long>();
        // Public graph endpoints use visitor rules even when the owner requests them.
        var sessions = sharing.visibleSessions(profile);
        var aiMaps = aiNodes ? mindmaps.of(sessions) : Map.<Long, SessionMindmapResponse>of();
        for (var session : sessions) {
            var sessionTags = sharing.visibleTags(profile, session.getTags()).stream().map(CommunityTags::key).distinct()
                    .map(tagIndex::get).filter(Objects::nonNull).toList();
            if (hashtagId != null && sessionTags.stream().noneMatch(t -> t.getId().equals(hashtagId))) continue;
            var part = aiNodes ? aiPart(session, aiMaps.get(session.getId()), indexedNodes)
                    : urlPart(session, profile, indexedNodes);
            leaves.addAll(part.nodes());
            edges.addAll(part.edges());
            for (var tag : sessionTags) {
                if (part.nodes().isEmpty()) break;
                visibleTags.putIfAbsent(tag.getId(), new BookmarkSnapshot.Tag(tag.getId(), ownerId, tag.getName()));
                part.entries().forEach(n -> edges.add(new BookmarkSnapshot.Edge("tag:" + tag.getId(), "node:" + n)));
                counts.merge(tag.getId(), (long) part.nodes().size(), Long::sum);
            }
        }
        for (String interest : profile.getInterests()) {
            if (sharing.isHidden(profile, interest)) continue;
            var tag = tagIndex.get(CommunityTags.key(interest));
            if (tag != null && (hashtagId == null || tag.getId().equals(hashtagId))) {
                visibleTags.putIfAbsent(tag.getId(), new BookmarkSnapshot.Tag(tag.getId(), ownerId, tag.getName()));
            }
        }
        if (hashtagId != null && !visibleTags.containsKey(hashtagId)) throw CommunityErrors.missing();
        var ranked = visibleTags.values().stream().filter(t -> counts.getOrDefault(t.hashtagId(), 0L) > 0)
                .map(t -> new TopTag(t.hashtagId(), t.name(), counts.get(t.hashtagId())))
                .sorted(Comparator.comparingLong(TopTag::nodeCount).reversed()
                        .thenComparing(t -> CommunityTags.key(t.name())).thenComparing(TopTag::hashtagId))
                .limit(5).toList();
        return new Graph(200, ownerId, ownerId, owner.getNickname(), graphTitle(owner.getNickname()),
                List.copyOf(visibleTags.values()), List.copyOf(leaves), List.copyOf(edges), ranked);
    }

    // 작성자 한 사람의 전체 그래프라 카드 제목이 없다
    private static String graphTitle(String nickname) {
        return nickname == null ? "전체 마인드맵" : nickname + "님의 전체 마인드맵";
    }

    // 세션의 태그 → URL 잎. 노드 행은 쓰기 때 만들어 두고, 없는 노드는 건너뛴다
    private GraphPart urlPart(BrowsingSession session, CommunityProfile profile, Map<String, CommunityNode> indexed) {
        boolean content = profile.getVisibility() == NodeVisibility.CONTENT;
        var parts = new ArrayList<BookmarkSnapshot.Node>();
        var seenUrls = new HashSet<String>();
        for (var source : session.getSources()) {
            if (!HttpUrl.isValid(source.url()) || !seenUrls.add(source.url())) continue;
            var node = indexed.get(session.getId() + ":" + SelectionKey.of(source.url()));
            if (node == null) continue;
            parts.add(new BookmarkSnapshot.Node(node.getId(),
                    content && source.title() != null ? source.title() : source.url(),
                    source.url(), content ? source.excerpt() : null, profile.getVisibility()));
        }
        return new GraphPart(parts, List.of(), parts.stream().map(BookmarkSnapshot.Node::nodeId).toList());
    }

    // 세션의 AI 마인드맵. 태그는 부모가 없는 노드(뿌리)에 잇는다
    private GraphPart aiPart(BrowsingSession session, SessionMindmapResponse mindmap, Map<String, CommunityNode> indexed) {
        var ids = new LinkedHashMap<String, Long>();
        var parts = new ArrayList<BookmarkSnapshot.Node>();
        for (var aiNode : mindmap.nodes()) {
            var node = indexed.get(session.getId() + ":" + SelectionKey.of("ai:" + aiNode.id()));
            if (node == null) continue;
            ids.put(aiNode.id(), node.getId());
            var citation = aiNode.citation();
            parts.add(new BookmarkSnapshot.Node(node.getId(), aiNode.keyword(), citation == null ? null : citation.url(),
                    citation == null ? null : citation.excerpt(), NodeVisibility.CONTENT));
        }
        var edges = new ArrayList<BookmarkSnapshot.Edge>();
        var children = new HashSet<String>();
        for (var edge : mindmap.edges()) {
            if (HIERARCHY.equals(edge.type()) && ids.containsKey(edge.from()) && ids.containsKey(edge.to())) {
                edges.add(new BookmarkSnapshot.Edge("node:" + ids.get(edge.from()), "node:" + ids.get(edge.to())));
                children.add(edge.to());
            }
        }
        var entries = ids.entrySet().stream().filter(e -> !children.contains(e.getKey())).map(Map.Entry::getValue).toList();
        return new GraphPart(parts, edges, entries);
    }

    public Profile profile(Long ownerId, Long viewerId) {
        var graph = graph(ownerId, null);
        var profile = profiles.read(ownerId);
        var totals = cards.totals(profile, viewerId);
        return new Profile(200, ownerId, graph.nickname(), profile.getBio(), profile.getVisibility(),
                totals.nodeCount(), graph.topHashtags(),
                profile.getInterests().stream().filter(t -> !sharing.isHidden(profile, t)).limit(10).toList(),
                likes.countByProfileId(ownerId), likes.existsByActorIdAndProfileId(viewerId, ownerId),
                follows.existsByActorIdAndTargetId(viewerId, ownerId), totals.exploringCount(),
                sharing.publicSessionCount(profile), pins.pinned(ownerId, -1L));
    }

    public Original original(Long viewerId, Long bookmarkId) {
        var bookmark = bookmarks.owned(viewerId, bookmarkId);
        if (bookmark.getSaveType() != SaveType.MINI_MINDMAP) throw new CustomException(ErrorCode.MINI_MINDMAP_REQUIRED);
        Long ownerId = bookmark.getSourceMindmapId();
        // 목록에서 숨겨진 북마크는 원본도 열리지 않는다
        var saved = bookmarks.currentSources(bookmark);
        if (ownerId == null || saved == null || !members.existsByIdAndWithdrawnAtIsNull(ownerId)) {
            return new Original(200, false, null, List.of());
        }
        var sessionOf = new HashMap<Long, Long>();
        nodes.findByOwnerId(ownerId).forEach(n -> sessionOf.put(n.getId(), n.getSessionId()));
        var graph = graph(ownerId, null);
        if (!saved.nodeIds().isEmpty() && !hasAny(graph, saved.nodeIds())) {
            // 공개 범위를 내용까지로 바꾸기 전에 URL 노드로 저장했거나 세션에 AI 결과가 없으면 URL 그래프에서 원본을 보여준다
            var urls = urlGraph(ownerId);
            if (hasAny(urls, saved.nodeIds()) || highlights(graph, saved, sessionOf).isEmpty()) graph = urls;
        }
        return new Original(200, !graph.nodes().isEmpty(), graph, highlights(graph, saved, sessionOf));
    }

    private static boolean hasAny(Graph graph, Set<Long> nodeIds) {
        return graph.nodes().stream().map(BookmarkSnapshot.Node::nodeId).anyMatch(nodeIds::contains);
    }

    // 저장한 id 가 그래프에 있으면 그 노드, 한 세션의 id 가 하나도 없으면(공개 범위 변경 · 재분석) 그 세션의 그래프 노드 전부
    private static List<Long> highlights(Graph graph, BookmarkQueryService.Sources saved, Map<Long, Long> sessionOf) {
        var ids = graph.nodes().stream().map(BookmarkSnapshot.Node::nodeId).toList();
        var matched = new HashSet<Long>();
        ids.stream().filter(saved.nodeIds()::contains).forEach(id -> matched.add(sessionOf.get(id)));
        return ids.stream().filter(id -> saved.nodeIds().contains(id)
                || saved.sessionIds().contains(sessionOf.get(id)) && !matched.contains(sessionOf.get(id))).toList();
    }

    private record GraphPart(List<BookmarkSnapshot.Node> nodes, List<BookmarkSnapshot.Edge> edges, List<Long> entries) { }
    public record TopTag(Long hashtagId, String name, long nodeCount) { }
    public record Graph(int code, Long mindmapId, Long ownerId, String nickname, String title,
            List<BookmarkSnapshot.Tag> hashtags, List<BookmarkSnapshot.Node> nodes,
            List<BookmarkSnapshot.Edge> edges, List<TopTag> topHashtags) { }
    public record Profile(int code, Long userId, String nickname, String bio, NodeVisibility visibility,
            long totalNodeCount, List<TopTag> topHashtags, List<String> interests, long likeCount, boolean likedByMe,
            boolean following, long exploringCount, long sharedSessionCount, List<SharedSessionView> pinnedSessions) { }
    public record Original(int code, boolean sourceAvailable, Graph mindmap, List<Long> highlightedNodeIds) { }
}
