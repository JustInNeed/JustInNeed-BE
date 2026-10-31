package com.justinneed.community.bookmark;

import com.justinneed.community.activity.*;
import com.justinneed.community.api.*;
import com.justinneed.community.reaction.*;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class BookmarkCommandService {
    private final CommunityBookmarkRepository bookmarks;
    private final TargetResolver targets;
    private final InteractionLocks locks;
    private final ActivityRecorder activity;
    private final Clock clock;
    public BookmarkCommandService(CommunityBookmarkRepository bookmarks, TargetResolver targets,
            InteractionLocks locks, ActivityRecorder activity, Clock clock) {
        this.bookmarks = bookmarks; this.targets = targets; this.locks = locks;
        this.activity = activity; this.clock = clock;
    }
    public Saved save(Long actor, TargetType type, List<Long> ids, SaveType saveType, Long source) {
        if (ids == null || ids.isEmpty() || ids.size() > 100 || new HashSet<>(ids).size() != ids.size()) {
            throw new CustomException(ErrorCode.INVALID_BOOKMARK_SELECTION);
        }
        var owners = ids.stream().map(id -> targets.owner(type, id)).distinct().toList();
        locks.acquire(actor, owners);
        if (saveType == SaveType.MINI_MINDMAP && (source == null || owners.size() != 1 || !owners.get(0).equals(source))) {
            throw new CustomException(ErrorCode.BOOKMARK_SOURCE_MISMATCH);
        }
        if (saveType == SaveType.HASHTAG_BUNDLE && source != null
                && (owners.size() != 1 || !owners.get(0).equals(source))) {
            throw new CustomException(ErrorCode.BOOKMARK_SOURCE_MISMATCH);
        }
        // Validate the complete selection before saving a snapshot or recording activity.
        var resolved = new LinkedHashMap<Long, TargetResolver.Resolved>();
        ids.forEach(id -> resolved.put(id, targets.resolve(type, id)));
        var key = SelectionKey.of(type + "|" + saveType + "|" + source + "|" + ids.stream().sorted().toList());
        var existing = bookmarks.findByUserIdAndSelectionKey(actor, key);
        if (existing.isPresent()) return view(existing.get());
        var tags = new LinkedHashSet<BookmarkSnapshot.Tag>();
        var nodes = new LinkedHashSet<BookmarkSnapshot.Node>();
        var edges = new LinkedHashSet<BookmarkSnapshot.Edge>();
        resolved.values().forEach(t -> { tags.addAll(t.tags()); nodes.addAll(t.nodes()); edges.addAll(t.edges()); });
        if (saveType == SaveType.HASHTAG_BUNDLE && tags.isEmpty()) throw new CustomException(ErrorCode.BOOKMARK_HASHTAG_REQUIRED);
        var snapshot = new BookmarkSnapshot("저장한 마인드맵", List.copyOf(tags),
                saveType == SaveType.MINI_MINDMAP ? List.copyOf(nodes) : List.of(),
                saveType == SaveType.MINI_MINDMAP ? List.copyOf(edges) : List.of());
        var saved = bookmarks.save(new CommunityBookmark(actor, saveType, type, key, source, ids, snapshot, clock.instant()));
        resolved.forEach((id, t) -> activity.record(actor, type, id, ActivityKind.BOOKMARK, t.tags()));
        return view(saved);
    }
    public void delete(Long actor, Long id) {
        locks.acquire(actor, List.of());
        bookmarks.delete(bookmarks.findByIdAndUserId(id, actor).orElseThrow(CommunityErrors::missing));
    }
    private Saved view(CommunityBookmark b) { return new Saved(201, b.getId(), b.getTargetIds(), b.getSaveType()); }
    public record Saved(int code, Long bookmarkId, List<Long> targetIds, SaveType saveType) { }
}
