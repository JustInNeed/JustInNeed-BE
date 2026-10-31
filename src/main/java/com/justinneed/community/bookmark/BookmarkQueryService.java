package com.justinneed.community.bookmark;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.graph.CommunityNode;
import com.justinneed.community.graph.CommunityNodeRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
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
 * 북마크 읽기. 북마크는 원본의 지금 상태를 따르며, 저장된 행 · 스냅샷은 그대로 두고 읽을 때 거른다.
 *
 * <pre>
 * 노드     = 원본 세션(community_nodes.session_id)이 지금 공개가 아니면 숨긴다 (CommunitySharing 의 공개 규칙)
 * 미니맵   = 숨긴 노드 · 그 연결선 · 그 노드에만 달린 태그를 빼고, 남은 노드가 없으면 카드를 숨긴다
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
    public BookmarkQueryService(CommunityBookmarkRepository bookmarks, CommunityProfiles profiles, CommunityTags tags,
            CommunityNodeRepository nodes, CommunitySharing sharing) {
        this.bookmarks = bookmarks; this.profiles = profiles; this.tags = tags;
        this.nodes = nodes; this.sharing = sharing;
    }

    public Bundles bundles(Long userId, int page, int size) {
        profiles.read(userId);
        var found = page(userId, SaveType.HASHTAG_BUNDLE, page, size, this::bundle);
        return new Bundles(200, found.items(), found.hasNext());
    }
    public Mindmaps mindmaps(Long userId, int page, int size) {
        profiles.read(userId);
        var found = page(userId, SaveType.MINI_MINDMAP, page, size, this::mindmap);
        return new Mindmaps(200, found.items(), found.hasNext());
    }
    /** 목록에 보이는 그대로의 미니 마인드맵. 숨겨진 북마크면 null. */
    public Mindmap currentMindmap(CommunityBookmark bookmark) {
        return mindmap(bookmark, visible(List.of(bookmark)));
    }
    /** 최근 저장 기반 추천의 기준 해시태그. 보이는 북마크만 최근 20개를 세고, 저장한 순서 그대로 돌려준다. */
    public List<String> recentHashtags(Long userId) {
        var names = new ArrayList<String>();
        int[] counted = {0};
        scan(paging -> bookmarks.findByUserId(userId, paging), this::shownHashtags, shown -> {
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
        return mindmap == null ? null : mindmap.hashtags().stream().map(BookmarkSnapshot.Tag::name).toList();
    }
    private <T> VisiblePage<T> page(Long userId, SaveType type, int page, int size,
            BiFunction<CommunityBookmark, Visible, T> view) {
        long[] skipped = {paging(page, size).getOffset()};
        var items = new ArrayList<T>();
        scan(paging -> bookmarks.findByUserIdAndSaveType(userId, type, paging), view, item -> {
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
    private <T> void scan(Function<Pageable, Slice<CommunityBookmark>> rows,
            BiFunction<CommunityBookmark, Visible, T> view, Predicate<T> visitor) {
        for (int batch = 0; ; batch++) {
            var slice = rows.apply(PageRequest.of(batch, SCAN_SIZE, NEWEST_FIRST));
            var visible = visible(slice.getContent());
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
        return names.isEmpty() ? null : new Bundle(b.getId(), names, b.getSavedAt());
    }
    private Mindmap mindmap(CommunityBookmark b, Visible visible) {
        var s = b.getSnapshot();
        var live = s.nodes().stream().filter(n -> visible.nodeIds().contains(n.nodeId())).toList();
        if (live.isEmpty() && !s.nodes().isEmpty()) return null;
        var liveKeys = live.stream().map(n -> "node:" + n.nodeId()).collect(Collectors.toSet());
        var liveEdges = s.edges().stream().filter(e -> liveKeys.contains(e.to())).toList();
        // 원본이 숨겨져 노드를 모두 잃은 태그는 뺀다. 처음부터 연결선이 없던 태그는 그대로 둔다
        var shown = s.hashtags().stream().filter(t -> visible.hashtagIds().contains(t.hashtagId()))
                .filter(t -> hasEdge(liveEdges, t) || !hasEdge(s.edges(), t)).toList();
        if (shown.isEmpty() && !s.hashtags().isEmpty()) return null;
        var hidden = new HashSet<String>();
        s.hashtags().stream().filter(t -> !shown.contains(t)).forEach(t -> hidden.add("tag:" + t.hashtagId()));
        var edges = liveEdges.stream().filter(e -> !hidden.contains(e.from())).toList();
        var orphaned = new HashSet<String>();
        liveEdges.stream().filter(e -> hidden.contains(e.from())).forEach(e -> orphaned.add(e.to()));
        edges.forEach(e -> orphaned.remove(e.to()));
        var kept = live.stream().filter(n -> !orphaned.contains("node:" + n.nodeId())).toList();
        return new Mindmap(b.getId(), b.getSourceMindmapId(), s.title(), shown, kept, edges, b.getSavedAt());
    }
    private static boolean hasEdge(List<BookmarkSnapshot.Edge> edges, BookmarkSnapshot.Tag tag) {
        return edges.stream().anyMatch(e -> e.from().equals("tag:" + tag.hashtagId()));
    }
    private Visible visible(List<CommunityBookmark> batch) {
        var hashtagIds = tags.publicIds(batch.stream().flatMap(b -> b.getSnapshot().hashtags().stream())
                .map(BookmarkSnapshot.Tag::hashtagId).toList());
        var nodeIds = new HashSet<Long>();
        batch.forEach(b -> {
            b.getSnapshot().nodes().forEach(n -> nodeIds.add(n.nodeId()));
            if (b.getTargetType() == TargetType.NODE) nodeIds.addAll(b.getTargetIds());
        });
        return new Visible(hashtagIds, liveNodeIds(nodeIds));
    }
    // 원본 세션이 지금 공개인 노드. 노드 행이 없으면 원본을 알 수 없어 보이지 않는 것으로 둔다
    private Set<Long> liveNodeIds(Set<Long> nodeIds) {
        var rows = nodes.findAllById(nodeIds);
        var publicSessions = sharing.publicSessionIds(
                rows.stream().map(CommunityNode::getSessionId).collect(Collectors.toSet()));
        return rows.stream().filter(n -> publicSessions.contains(n.getSessionId()))
                .map(CommunityNode::getId).collect(Collectors.toSet());
    }
    public CommunityBookmark owned(Long userId, Long bookmarkId) {
        profiles.read(userId);
        return bookmarks.findByIdAndUserId(bookmarkId, userId).orElseThrow(CommunityErrors::missing);
    }
    public static PageRequest paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw CommunityErrors.invalid("page >= 0 and size between 1 and 100 required");
        return PageRequest.of(page, size, NEWEST_FIRST);
    }
    private record Visible(Set<Long> hashtagIds, Set<Long> nodeIds) { }
    private record VisiblePage<T>(List<T> items, boolean hasNext) { }
    public record Bundle(Long bookmarkId, List<String> hashtags, Instant savedAt) { }
    public record Bundles(int code, List<Bundle> bundles, boolean hasNext) { }
    public record Mindmap(Long bookmarkId, Long sourceMindmapId, String title,
            List<BookmarkSnapshot.Tag> hashtags, List<BookmarkSnapshot.Node> nodes,
            List<BookmarkSnapshot.Edge> edges, Instant savedAt) { }
    public record Mindmaps(int code, List<Mindmap> mindmaps, boolean hasNext) { }
}
