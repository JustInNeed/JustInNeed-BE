package com.justinneed.community.bookmark;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.service.CommunityProfiles;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookmarkQueryService {
    private final CommunityBookmarkRepository bookmarks;
    private final CommunityProfiles profiles;
    public BookmarkQueryService(CommunityBookmarkRepository bookmarks, CommunityProfiles profiles) {
        this.bookmarks = bookmarks; this.profiles = profiles;
    }

    public Bundles bundles(Long userId, int page, int size) {
        profiles.read(userId);
        var slice = bookmarks.findByUserIdAndSaveType(userId, SaveType.HASHTAG_BUNDLE, paging(page, size));
        return new Bundles(200, slice.stream().map(b -> new Bundle(b.getId(),
                b.getSnapshot().hashtags().stream().map(BookmarkSnapshot.Tag::name).toList(), b.getSavedAt())).toList(),
                slice.hasNext());
    }
    public Mindmaps mindmaps(Long userId, int page, int size) {
        profiles.read(userId);
        var slice = bookmarks.findByUserIdAndSaveType(userId, SaveType.MINI_MINDMAP, paging(page, size));
        return new Mindmaps(200, slice.stream().map(b -> new Mindmap(b.getId(), b.getSourceMindmapId(),
                b.getSnapshot().title(), b.getSnapshot().nodes(), b.getSnapshot().edges(), b.getSavedAt())).toList(),
                slice.hasNext());
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
            List<BookmarkSnapshot.Node> nodes, List<BookmarkSnapshot.Edge> edges, Instant savedAt) { }
    public record Mindmaps(int code, List<Mindmap> mindmaps, boolean hasNext) { }
}
