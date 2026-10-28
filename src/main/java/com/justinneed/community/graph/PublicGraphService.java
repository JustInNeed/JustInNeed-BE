package com.justinneed.community.graph;

import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.api.SelectionKey;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.catalog.*;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.pin.SharedSessionService;
import com.justinneed.community.reaction.ProfileLikeRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import java.net.URI;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PublicGraphService {
    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;
    private final CommunityTags tags;
    private final CommunityNodeRepository nodes;
    private final MemberRepository members;
    private final ProfileLikeRepository likes;
    private final SharedSessionService pins;
    private final BookmarkQueryService bookmarks;

    public PublicGraphService(CommunityProfiles profiles, CommunitySharing sharing, CommunityTags tags,
            CommunityNodeRepository nodes, MemberRepository members, ProfileLikeRepository likes,
            SharedSessionService pins, BookmarkQueryService bookmarks) {
        this.profiles = profiles; this.sharing = sharing; this.tags = tags; this.nodes = nodes;
        this.members = members; this.likes = likes; this.pins = pins; this.bookmarks = bookmarks;
    }

    public Graph graph(Long ownerId, Long hashtagId) {
        var profile = profiles.lock(ownerId);
        var owner = members.findById(ownerId).orElseThrow(CommunityErrors::missing);
        var tagIndex = new LinkedHashMap<String, CommunityHashtag>();
        tags.owned(ownerId).forEach(t -> tagIndex.put(t.getNormalizedName(), t));
        var indexedNodes = new HashMap<String, CommunityNode>();
        nodes.findByOwnerId(ownerId).forEach(n -> indexedNodes.put(n.getSessionId() + ":" + n.getSourceKey(), n));
        var visibleTags = new LinkedHashMap<Long, BookmarkSnapshot.Tag>();
        var leaves = new ArrayList<BookmarkSnapshot.Node>();
        var edges = new LinkedHashSet<BookmarkSnapshot.Edge>();
        var counts = new HashMap<Long, Long>();
        // Public graph endpoints use visitor rules even when the owner requests them.
        for (var session : sharing.visibleSessions(profile, -1L)) {
            var sessionTags = session.getTags().stream().map(CommunityTags::key).distinct().map(tagIndex::get).toList();
            if (hashtagId != null && sessionTags.stream().noneMatch(t -> t.getId().equals(hashtagId))) continue;
            var seenUrls = new HashSet<String>();
            for (var source : session.getSources()) {
                if (!httpUrl(source.url()) || !seenUrls.add(source.url())) continue;
                String key = SelectionKey.of(source.url());
                var node = indexedNodes.computeIfAbsent(session.getId() + ":" + key,
                        ignored -> nodes.save(new CommunityNode(ownerId, session.getId(), key)));
                boolean content = profile.getVisibility() == NodeVisibility.CONTENT;
                leaves.add(new BookmarkSnapshot.Node(node.getId(),
                        content && source.title() != null ? source.title() : source.url(),
                        source.url(), content ? source.excerpt() : null, profile.getVisibility()));
                for (var tag : sessionTags) {
                    visibleTags.putIfAbsent(tag.getId(), new BookmarkSnapshot.Tag(tag.getId(), ownerId, tag.getName()));
                    edges.add(new BookmarkSnapshot.Edge("tag:" + tag.getId(), "node:" + node.getId()));
                    counts.merge(tag.getId(), 1L, Long::sum);
                }
            }
        }
        for (String interest : profile.getInterests()) {
            if (sharing.isHidden(profile, interest)) continue;
            var tag = tagIndex.get(CommunityTags.key(interest));
            if (hashtagId == null || tag.getId().equals(hashtagId)) {
                visibleTags.putIfAbsent(tag.getId(), new BookmarkSnapshot.Tag(tag.getId(), ownerId, tag.getName()));
            }
        }
        if (hashtagId != null && !visibleTags.containsKey(hashtagId)) throw CommunityErrors.missing();
        var ranked = visibleTags.values().stream().filter(t -> counts.getOrDefault(t.hashtagId(), 0L) > 0)
                .map(t -> new TopTag(t.hashtagId(), t.name(), counts.get(t.hashtagId())))
                .sorted(Comparator.comparingLong(TopTag::nodeCount).reversed()
                        .thenComparing(t -> CommunityTags.key(t.name())).thenComparing(TopTag::hashtagId))
                .limit(5).toList();
        return new Graph(200, ownerId, ownerId, owner.getNickname(), "Community mindmap",
                List.copyOf(visibleTags.values()), List.copyOf(leaves), List.copyOf(edges), ranked);
    }

    public Profile profile(Long ownerId, Long viewerId) {
        var graph = graph(ownerId, null);
        var profile = profiles.read(ownerId);
        return new Profile(200, ownerId, graph.nickname(), graph.nodes().size(), graph.topHashtags(),
                profile.getInterests().stream().filter(t -> !sharing.isHidden(profile, t)).limit(10).toList(),
                likes.countByProfileId(ownerId), likes.existsByActorIdAndProfileId(viewerId, ownerId),
                pins.list(ownerId, -1L));
    }

    public Original original(Long viewerId, Long bookmarkId) {
        var bookmark = bookmarks.owned(viewerId, bookmarkId);
        if (bookmark.getSaveType() != SaveType.MINI_MINDMAP) throw CommunityErrors.invalid("Mini mindmap required");
        Long ownerId = bookmark.getSourceMindmapId();
        if (ownerId == null || !members.existsById(ownerId)) return new Original(200, false, null, List.of());
        var graph = graph(ownerId, null);
        var saved = bookmark.getSnapshot().nodes().stream().map(BookmarkSnapshot.Node::nodeId).toList();
        return new Original(200, !graph.nodes().isEmpty(), graph,
                graph.nodes().stream().map(BookmarkSnapshot.Node::nodeId).filter(saved::contains).toList());
    }

    private boolean httpUrl(String value) {
        if (value == null) return false;
        try {
            var uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException error) { return false; }
    }

    public record TopTag(Long hashtagId, String name, long nodeCount) { }
    public record Graph(int code, Long mindmapId, Long ownerId, String nickname, String title,
            List<BookmarkSnapshot.Tag> hashtags, List<BookmarkSnapshot.Node> nodes,
            List<BookmarkSnapshot.Edge> edges, List<TopTag> topHashtags) { }
    public record Profile(int code, Long userId, String nickname, long totalNodeCount,
            List<TopTag> topHashtags, List<String> interests, long likeCount, boolean likedByMe,
            List<SharedSessionView> pinnedSessions) { }
    public record Original(int code, boolean sourceAvailable, Graph mindmap, List<Long> highlightedNodeIds) { }
}
