package com.justinneed.community.bookmark;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.api.HttpUrl;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.community.catalog.CommunityHashtag;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.graph.CommunityNode;
import com.justinneed.community.graph.CommunityNodeRepository;
import com.justinneed.community.repository.CommunityMemberRepository;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.Instant;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 북마크 읽기. 미니 마인드맵은 원본의 지금 상태를 보여주고, 저장된 행 · 스냅샷은 그대로 둔 채 읽을 때 맞춘다.
 *
 * <pre>
 * 노드     = 원본 세션(community_nodes.session_id)이 지금 공개가 아니면 숨긴다 (CommunitySharing 의 공개 규칙)
 * 미니맵   = 남은 노드가 없으면 숨긴다. 스냅샷 제목은 쓰지 않는다
 *            세션 하나 = 그 카드의 제목 · 요약 · 태그 · 노드 수 · 좋아요
 *            세션 여러 개 = 제목 · 요약 null, 태그는 공개 세션 카드의 태그를 합치고, 숫자는 보이는 노드 수 · 공개 세션 좋아요 합
 *            작성자가 지금 URL만이면 노드는 라벨 = URL · 내용 null 로만 보이고, URL 이 없는 노드는 빠지며 세션마다 같은 URL 은 하나만 남는다
 *            연결선은 스냅샷의 노드 간 연결(내용까지만)에 세션의 지금 태그 → 뿌리 노드를 더해 만든다
 * 묶음     = 노드를 골라 저장했으면 대상 노드가 모두 숨겨질 때 숨긴다
 *            해시태그를 골라 저장한 묶음은 세션과 이어진 값이 없어 해시태그 규칙만 따른다
 * 해시태그 = 지금 비공개거나 없는 해시태그는 숨긴다
 * </pre>
 *
 * 페이지는 보이는 북마크만 세어 나눠, 숨겨진 북마크가 빈 자리나 잘못된 hasNext 를 남기지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class BookmarkQueryService {
    private static final int SCAN_SIZE = 100;
    private static final int RECENT_BOOKMARKS = 20;
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("id"));

    private final CommunityBookmarkRepository bookmarks;
    private final CommunityProfiles profiles;
    private final CommunityTags tags;
    private final CommunityNodeRepository nodes;
    private final CommunitySharing sharing;
    private final CommunityCardReader cards;
    private final CommunityMemberRepository members;
    private final CommunityProfileRepository profileRows;
    public BookmarkQueryService(CommunityBookmarkRepository bookmarks, CommunityProfiles profiles, CommunityTags tags,
            CommunityNodeRepository nodes, CommunitySharing sharing, CommunityCardReader cards,
            CommunityMemberRepository members, CommunityProfileRepository profileRows) {
        this.bookmarks = bookmarks; this.profiles = profiles; this.tags = tags;
        this.nodes = nodes; this.sharing = sharing; this.cards = cards;
        this.members = members; this.profileRows = profileRows;
    }

    public Bundles bundles(Long userId, int page, int size) {
        profiles.read(userId);
        var found = page(userId, SaveType.HASHTAG_BUNDLE, page, size, this::bundle);
        return new Bundles(200, found.items(), found.hasNext());
    }
    public Mindmaps mindmaps(Long userId, int page, int size) {
        profiles.read(userId);
        var found = page(userId, SaveType.MINI_MINDMAP, page, size, this::mindmap);
        return new Mindmaps(found.items(), found.hasNext());
    }
    /** 목록에 보이는 그대로의 미니 마인드맵. 숨겨진 북마크면 null. */
    public Mindmap currentMindmap(CommunityBookmark bookmark) {
        return mindmap(bookmark, visible(bookmark.getUserId(), List.of(bookmark)));
    }
    /** 최근 저장 기반 추천의 기준 해시태그. 보이는 북마크만 최근 20개를 세고, 저장한 순서 그대로 돌려준다. */
    public List<String> recentHashtags(Long userId) {
        var names = new ArrayList<String>();
        int[] counted = {0};
        scan(userId, paging -> bookmarks.findByUserId(userId, paging), this::shownHashtags, shown -> {
            names.addAll(shown);
            return ++counted[0] < RECENT_BOOKMARKS;
        });
        return names;
    }
    private List<String> shownHashtags(CommunityBookmark b, Visible visible) {
        if (b.getSaveType() == SaveType.HASHTAG_BUNDLE) {
            var bundle = bundle(b, visible);
            return bundle == null ? null : bundle.hashtags();
        }
        var mindmap = mindmap(b, visible);
        return mindmap == null ? null : mindmap.hashtags().stream().map(Hashtag::name).toList();
    }
    private <T> VisiblePage<T> page(Long userId, SaveType type, int page, int size,
            BiFunction<CommunityBookmark, Visible, T> view) {
        long[] skipped = {paging(page, size).getOffset()};
        var items = new ArrayList<T>();
        scan(userId, paging -> bookmarks.findByUserIdAndSaveType(userId, type, paging), view, item -> {
            if (skipped[0] > 0) {
                skipped[0]--;
                return true;
            }
            items.add(item);
            return items.size() <= size;
        });
        return new VisiblePage<>(items.stream().limit(size).toList(), items.size() > size);
    }
    // 최신순으로 훑으며 보이는 북마크(view 가 null 이 아닌 것)를 visitor 에 넘긴다. visitor 가 false 면 멈춘다
    private <T> void scan(Long userId, Function<Pageable, Slice<CommunityBookmark>> rows,
            BiFunction<CommunityBookmark, Visible, T> view, Predicate<T> visitor) {
        for (int batch = 0; ; batch++) {
            var slice = rows.apply(PageRequest.of(batch, SCAN_SIZE, NEWEST_FIRST));
            var visible = visible(userId, slice.getContent());
            for (var bookmark : slice) {
                T item = view.apply(bookmark, visible);
                if (item != null && !visitor.test(item)) return;
            }
            if (!slice.hasNext()) return;
        }
    }
    private Bundle bundle(CommunityBookmark b, Visible visible) {
        var sources = b.getTargetType() == TargetType.NODE ? b.getTargetIds() : List.<Long>of();
        if (!sources.isEmpty() && sources.stream().noneMatch(visible.nodeIds()::contains)) return null;
        var names = b.getSnapshot().hashtags().stream().filter(t -> visible.hashtagIds().contains(t.hashtagId()))
                .map(BookmarkSnapshot.Tag::name).toList();
        return names.isEmpty() ? null : new Bundle(b.getId(), names, b.getSavedAt(), b.getSaveType(), b.getTargetType(),
                savableTargets(b, visible), b.getSourceMindmapId());
    }
    private Mindmap mindmap(CommunityBookmark b, Visible visible) {
        var s = b.getSnapshot();
        var live = s.nodes().stream().filter(n -> visible.nodeIds().contains(n.nodeId())).toList();
        if (live.isEmpty() && !s.nodes().isEmpty()) return null;
        Long ownerId = b.getSourceMindmapId();
        var hashtags = new LinkedHashMap<Long, Hashtag>();
        var shown = new ArrayList<BookmarkSnapshot.Node>();
        var edges = new ArrayList<BookmarkSnapshot.Edge>();
        // 관심사 태그만 고른 저장은 노드가 없어 이어진 세션도 없다. 그때는 저장한 태그를 공개 규칙으로만 거른다
        if (live.isEmpty()) {
            s.hashtags().stream().filter(t -> visible.hashtagIds().contains(t.hashtagId()))
                    .forEach(t -> hashtags.put(t.hashtagId(), new Hashtag(t.hashtagId(), t.name())));
            if (hashtags.isEmpty() && !s.hashtags().isEmpty()) return null;
        }
        boolean content = visible.contentOwners().contains(ownerId);
        var bySession = live.stream().collect(Collectors.groupingBy(n -> visible.sessionOf().get(n.nodeId()),
                LinkedHashMap::new, Collectors.toList()));
        bySession.forEach((session, part) -> {
            var current = currentTags(session, ownerId, visible);
            current.forEach(tag -> hashtags.putIfAbsent(tag.hashtagId(), tag));
            var parts = content ? contentNodes(part) : urlNodes(part);
            shown.addAll(parts);
            var keys = parts.stream().map(n -> "node:" + n.nodeId()).collect(Collectors.toSet());
            // URL만은 공개 그래프처럼 태그 → URL 뿐이다. AI 구조를 남기지 않는다
            var inner = !content ? List.<BookmarkSnapshot.Edge>of() : s.edges().stream()
                    .filter(e -> keys.contains(e.from()) && keys.contains(e.to())).toList();
            edges.addAll(inner);
            var children = inner.stream().map(BookmarkSnapshot.Edge::to).collect(Collectors.toSet());
            parts.stream().map(n -> "node:" + n.nodeId()).filter(key -> !children.contains(key)).forEach(key ->
                    current.forEach(tag -> edges.add(new BookmarkSnapshot.Edge("tag:" + tag.hashtagId(), key))));
        });
        var sources = s.nodes().stream().map(n -> visible.sessionOf().get(n.nodeId())).filter(Objects::nonNull).distinct().toList();
        Long sessionId = b.getCardSessionId() != null ? b.getCardSessionId() : sources.size() == 1 ? sources.get(0) : null;
        var card = sessionId == null ? null : visible.cards().get(sessionId);
        long likeCount = bySession.keySet().stream().map(visible.cards()::get).filter(Objects::nonNull)
                .mapToLong(CommunityCard::likeCount).sum();
        return new Mindmap(b.getId(), sessionId, b.getSourceMindmapId(),
                new CommunityCard.Owner(ownerId, visible.nicknames().get(ownerId)),
                card == null ? null : card.title(), card == null ? null : card.snippet(),
                List.copyOf(hashtags.values()), List.copyOf(shown), List.copyOf(edges),
                card == null ? shown.size() : card.nodeCount(), likeCount, b.getSavedAt(), b.getSaveType(),
                b.getTargetType(), savableTargets(b, visible), b.getCardSessionId() != null);
    }
    private static List<BookmarkSnapshot.Node> contentNodes(List<BookmarkSnapshot.Node> nodes) {
        return nodes.stream().map(n -> new BookmarkSnapshot.Node(n.nodeId(), n.label(), n.sourceUrl(), n.content(),
                NodeVisibility.CONTENT)).toList();
    }
    // AI 라벨 · 내용은 쓰지 않는다. http(s) URL 이 없는 노드(AI 뿌리 · 주제)는 보여줄 값이 없어 뺀다
    private static List<BookmarkSnapshot.Node> urlNodes(List<BookmarkSnapshot.Node> nodes) {
        var urls = new HashSet<String>();
        return nodes.stream().filter(n -> HttpUrl.isValid(n.sourceUrl()) && urls.add(n.sourceUrl()))
                .map(n -> new BookmarkSnapshot.Node(n.nodeId(), n.sourceUrl(), n.sourceUrl(), null, NodeVisibility.URL_ONLY))
                .toList();
    }
    // 카드의 태그는 이미 비공개 태그를 뺀 것이다. 행이 없는 옛 태그는 그래프처럼 건너뛴다
    private static List<Hashtag> currentTags(Long sessionId, Long ownerId, Visible visible) {
        var card = visible.cards().get(sessionId);
        if (card == null) return List.of();
        var index = visible.tagIndex().getOrDefault(ownerId, Map.of());
        return card.hashtags().stream().map(CommunityTags::key).distinct().map(index::get).filter(Objects::nonNull)
                .map(tag -> new Hashtag(tag.getId(), tag.getName())).toList();
    }
    // 되돌리기가 그대로 다시 보낼 대상. 지금 숨겨진 대상이 섞이면 저장 전체가 404 라 뺀다
    private static List<Long> savableTargets(CommunityBookmark b, Visible visible) {
        var shown = b.getTargetType() == TargetType.NODE ? visible.nodeIds() : visible.hashtagIds();
        return b.getTargetIds().stream().filter(shown::contains).toList();
    }
    private Visible visible(Long viewerId, List<CommunityBookmark> batch) {
        var hashtagIds = new HashSet<Long>();
        var nodeIds = new HashSet<Long>();
        batch.forEach(b -> {
            b.getSnapshot().hashtags().forEach(t -> hashtagIds.add(t.hashtagId()));
            b.getSnapshot().nodes().forEach(n -> nodeIds.add(n.nodeId()));
            (b.getTargetType() == TargetType.NODE ? nodeIds : hashtagIds).addAll(b.getTargetIds());
        });
        var sessionOf = nodes.findAllById(nodeIds).stream()
                .collect(Collectors.toMap(CommunityNode::getId, CommunityNode::getSessionId));
        var publicSessions = sharing.publicSessionIds(new HashSet<>(sessionOf.values()));
        // 원본 세션이 지금 공개인 노드. 노드 행이 없으면 원본을 알 수 없어 보이지 않는 것으로 둔다
        var liveNodeIds = sessionOf.entrySet().stream().filter(e -> publicSessions.contains(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        // 카드는 미니 마인드맵만 쓴다. 묶음은 스냅샷에 노드가 없어 여기서 빠진다
        var cardSessions = batch.stream().flatMap(b -> b.getSnapshot().nodes().stream()).map(n -> sessionOf.get(n.nodeId()))
                .filter(Objects::nonNull).filter(publicSessions::contains).collect(Collectors.toSet());
        var cardById = cards.cards(cardSessions, viewerId).stream()
                .collect(Collectors.toMap(CommunityCard::mindmapId, Function.identity()));
        var ownerIds = batch.stream().filter(b -> b.getSaveType() == SaveType.MINI_MINDMAP)
                .map(CommunityBookmark::getSourceMindmapId).filter(Objects::nonNull).collect(Collectors.toSet());
        var nicknames = new HashMap<Long, String>();
        if (!ownerIds.isEmpty()) members.findActiveByIdIn(ownerIds).forEach(m -> nicknames.put(m.getId(), m.getNickname()));
        var contentOwners = profileRows.findAllById(ownerIds).stream().filter(p -> p.getVisibility() == NodeVisibility.CONTENT)
                .map(CommunityProfile::getUserId).collect(Collectors.toSet());
        return new Visible(tags.publicIds(hashtagIds), liveNodeIds, sessionOf, cardById, tags.indexes(ownerIds),
                nicknames, contentOwners);
    }
    public CommunityBookmark owned(Long userId, Long bookmarkId) {
        profiles.read(userId);
        return bookmarks.findByIdAndUserId(bookmarkId, userId).orElseThrow(CommunityErrors::missing);
    }
    public static PageRequest paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new CustomException(ErrorCode.INVALID_PAGE_REQUEST);
        return PageRequest.of(page, size, NEWEST_FIRST);
    }
    private record Visible(Set<Long> hashtagIds, Set<Long> nodeIds, Map<Long, Long> sessionOf,
            Map<Long, CommunityCard> cards, Map<Long, Map<String, CommunityHashtag>> tagIndex,
            Map<Long, String> nicknames, Set<Long> contentOwners) { }
    private record VisiblePage<T>(List<T> items, boolean hasNext) { }
    public record Bundle(Long bookmarkId, List<String> hashtags, Instant savedAt, SaveType saveType,
            TargetType targetType, List<Long> targetIds, Long sourceMindmapId) { }
    public record Bundles(int code, List<Bundle> bundles, boolean hasNext) { }
    public record Hashtag(Long hashtagId, String name) { }
    /** sessionId 는 카드 세션, 아니면 노드가 한 세션일 때 그 세션. cardBookmark 면 되돌리기는 sessionId 로 PATCH, 아니면 target* 로 POST. */
    public record Mindmap(Long bookmarkId, Long sessionId, Long sourceMindmapId, CommunityCard.Owner owner,
            String title, String snippet, List<Hashtag> hashtags, List<BookmarkSnapshot.Node> nodes,
            List<BookmarkSnapshot.Edge> edges, long nodeCount, long likeCount, Instant savedAt, SaveType saveType,
            TargetType targetType, List<Long> targetIds, boolean cardBookmark) { }
    public record Mindmaps(List<Mindmap> mindmaps, boolean hasNext) { }
}
