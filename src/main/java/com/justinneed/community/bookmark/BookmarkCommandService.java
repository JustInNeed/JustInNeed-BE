package com.justinneed.community.bookmark;

import com.justinneed.community.activity.*;
import com.justinneed.community.api.*;
import com.justinneed.community.card.CommunityCard;
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
    private static final String DEFAULT_TITLE = "저장한 마인드맵";

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
        var snapshot = new BookmarkSnapshot(DEFAULT_TITLE, List.copyOf(tags),
                saveType == SaveType.MINI_MINDMAP ? List.copyOf(nodes) : List.of(),
                saveType == SaveType.MINI_MINDMAP ? List.copyOf(edges) : List.of());
        var saved = bookmarks.save(new CommunityBookmark(actor, saveType, type, key, source, ids, snapshot, clock.instant()));
        resolved.forEach((id, t) -> activity.record(actor, type, id, ActivityKind.BOOKMARK, t.tags()));
        return view(saved);
    }
    /**
     * 카드의 노드를 미니 마인드맵으로 저장한다. 이미 있으면 그대로 둔다. 누른 사람과 작성자를 잠근 트랜잭션에서 부른다.
     * 노드가 없으면 원본 세션과 이어지는 값이 없어 읽을 때 숨길 수 없으므로 저장하지 않는다.
     * URL만 공개한 작성자의 카드는 제목이 null 이라 기본 제목을 쓰고, 노드 라벨은 URL · 내용은 null 이다.
     */
    public void saveCard(Long actor, CommunityCard card) {
        var key = SelectionKey.of("CARD|" + card.mindmapId());
        if (bookmarks.findByUserIdAndSelectionKey(actor, key).isPresent()) return;
        var target = targets.card(card);
        if (target.nodes().isEmpty()) throw new CustomException(ErrorCode.BOOKMARK_NODE_REQUIRED);
        var snapshot = new BookmarkSnapshot(card.title() == null ? DEFAULT_TITLE : card.title(),
                target.tags(), target.nodes(), target.edges());
        // source_mindmap_id 는 지금까지처럼 작성자 id 다. 원본 보기가 이 값으로 작성자 그래프를 연다
        bookmarks.save(CommunityBookmark.ofCard(actor, key, card.mindmapId(), target.ownerId(),
                target.nodes().stream().map(BookmarkSnapshot.Node::nodeId).toList(), snapshot, clock.instant()));
        // 나도 탐색중과 같이 대상을 해시태그로 남겨 target_type 값이 늘지 않는다
        target.tags().forEach(tag ->
                activity.record(actor, TargetType.HASHTAG, tag.hashtagId(), ActivityKind.BOOKMARK, List.of(tag)));
    }
    public void deleteCard(Long actor, Long sessionId) {
        bookmarks.deleteCard(actor, sessionId);
    }
    public void delete(Long actor, Long id) {
        locks.acquire(actor, List.of());
        bookmarks.delete(bookmarks.findByIdAndUserId(id, actor).orElseThrow(CommunityErrors::missing));
    }
    private Saved view(CommunityBookmark b) { return new Saved(201, b.getId(), b.getTargetIds(), b.getSaveType()); }
    public record Saved(int code, Long bookmarkId, List<Long> targetIds, SaveType saveType) { }
}
