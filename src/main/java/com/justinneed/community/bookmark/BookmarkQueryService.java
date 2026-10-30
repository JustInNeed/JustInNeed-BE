package com.justinneed.community.bookmark;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.service.CommunityProfiles;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookmarkQueryService {
    private final CommunityBookmarkRepository bookmarks;
    private final CommunityProfiles profiles;
    private final CommunityTags tags;
    public BookmarkQueryService(CommunityBookmarkRepository bookmarks, CommunityProfiles profiles, CommunityTags tags) {
        this.bookmarks = bookmarks; this.profiles = profiles; this.tags = tags;
    }

    // Hashtags that are no longer public are hidden at read time; the stored snapshot stays as saved.
    public Bundles bundles(Long userId, int page, int size) {
        profiles.read(userId);
        var slice = bookmarks.findByUserIdAndSaveType(userId, SaveType.HASHTAG_BUNDLE, paging(page, size));
        var visible = publicIds(slice.getContent());
        return new Bundles(200, slice.stream().map(b -> new Bundle(b.getId(), b.getSnapshot().hashtags().stream()
                .filter(t -> visible.contains(t.hashtagId())).map(BookmarkSnapshot.Tag::name).toList(), b.getSavedAt()))
                .filter(b -> !b.hashtags().isEmpty()).toList(), slice.hasNext());
    }
    public Mindmaps mindmaps(Long userId, int page, int size) {
        profiles.read(userId);
        var slice = bookmarks.findByUserIdAndSaveType(userId, SaveType.MINI_MINDMAP, paging(page, size));
        var visible = publicIds(slice.getContent());
        return new Mindmaps(200, slice.stream().map(b -> mindmap(b, visible)).filter(Objects::nonNull).toList(),
                slice.hasNext());
    }
    private Mindmap mindmap(CommunityBookmark b, Set<Long> visible) {
        var s = b.getSnapshot();
        var shown = s.hashtags().stream().filter(t -> visible.contains(t.hashtagId())).toList();
        if (shown.isEmpty() && !s.hashtags().isEmpty()) return null;
        var hidden = new HashSet<String>();
        s.hashtags().stream().filter(t -> !visible.contains(t.hashtagId())).forEach(t -> hidden.add("tag:" + t.hashtagId()));
        var edges = s.edges().stream().filter(e -> !hidden.contains(e.from())).toList();
        var orphaned = new HashSet<String>();
        s.edges().stream().filter(e -> hidden.contains(e.from())).forEach(e -> orphaned.add(e.to()));
        edges.forEach(e -> orphaned.remove(e.to()));
        var nodes = s.nodes().stream().filter(n -> !orphaned.contains("node:" + n.nodeId())).toList();
        return new Mindmap(b.getId(), b.getSourceMindmapId(), s.title(), shown, nodes, edges, b.getSavedAt());
    }
    private Set<Long> publicIds(List<CommunityBookmark> page) {
        return tags.publicIds(page.stream().flatMap(b -> b.getSnapshot().hashtags().stream())
                .map(BookmarkSnapshot.Tag::hashtagId).toList());
    }
    public CommunityBookmark owned(Long userId, Long bookmarkId) {
        profiles.read(userId);
        return bookmarks.findByIdAndUserId(bookmarkId, userId).orElseThrow(CommunityErrors::missing);
    }
    public static PageRequest paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw CommunityErrors.invalid("page >= 0 and size between 1 and 100 required");
        return PageRequest.of(page, size, Sort.by(Sort.Order.desc("savedAt"), Sort.Order.desc("id")));
    }
    public record Bundle(Long bookmarkId, List<String> hashtags, Instant savedAt) { }
    public record Bundles(int code, List<Bundle> bundles, boolean hasNext) { }
    public record Mindmap(Long bookmarkId, Long sourceMindmapId, String title,
            List<BookmarkSnapshot.Tag> hashtags, List<BookmarkSnapshot.Node> nodes,
            List<BookmarkSnapshot.Edge> edges, Instant savedAt) { }
    public record Mindmaps(int code, List<Mindmap> mindmaps, boolean hasNext) { }
}
