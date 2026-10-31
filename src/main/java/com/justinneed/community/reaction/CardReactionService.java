package com.justinneed.community.reaction;

import com.justinneed.community.activity.ActivityKind;
import com.justinneed.community.activity.ActivityRecorder;
import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.BookmarkCommandService;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.card.CardCounts;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.community.card.CommunityCardRow;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드(세션) 단위 좋아요 · 나도 탐색중 · 북마크. 세 동작 모두 지금 공개된 카드에만 되고, 취소도 같다 (아니면 404).
 * 응답 숫자는 카드 응답과 같은 CardCounts 로 읽는다. 카드가 공개에서 빠져도 행은 남아 다시 공개하면 숫자가 돌아온다.
 *
 * <pre>
 * 좋아요     = on conflict 로 넣어 잠금이 없다. 내 카드는 400 (프로필 좋아요와 같다)
 * 나도 탐색중 = 새로 눌렀을 때 공개 태그마다 활동을 남긴다. ActivityRecorder 가 확인 후 넣어서 누른 사람을 잠근다
 * 북마크     = 스냅샷이 공개 범위 · 비공개 태그를 읽어서 기존 저장처럼 누른 사람과 작성자를 잠근다
 * </pre>
 */
@Service
@Transactional
public class CardReactionService {
    private final CommunityCardReader reader;
    private final CommunityCardRepository cards;
    private final CardCounts counts;
    private final MindmapLikeRepository likes;
    private final MindmapExplorationRepository explorations;
    private final BookmarkCommandService bookmarks;
    private final TargetResolver targets;
    private final InteractionLocks locks;
    private final ActivityRecorder activity;
    private final Clock clock;

    public CardReactionService(CommunityCardReader reader, CommunityCardRepository cards, CardCounts counts,
            MindmapLikeRepository likes, MindmapExplorationRepository explorations, BookmarkCommandService bookmarks,
            TargetResolver targets, InteractionLocks locks, ActivityRecorder activity, Clock clock) {
        this.reader = reader; this.cards = cards; this.counts = counts; this.likes = likes;
        this.explorations = explorations; this.bookmarks = bookmarks; this.targets = targets;
        this.locks = locks; this.activity = activity; this.clock = clock;
    }

    public LikeView like(Long actor, Long sessionId, boolean liked) {
        var card = visible(sessionId, actor);
        if (actor.equals(card.owner().userId())) throw new CustomException(ErrorCode.SELF_LIKE_NOT_ALLOWED);
        if (liked) likes.insertIfAbsent(actor, sessionId, LocalDateTime.now(clock));
        else likes.deletePair(actor, sessionId);
        var count = count(sessionId, actor);
        return new LikeView(count.likeCount(), count.likedByMe());
    }

    public ExploringView exploring(Long actor, Long sessionId, boolean exploring) {
        var card = visible(sessionId, actor);
        locks.acquire(actor, List.of());
        if (!exploring) explorations.deletePair(actor, sessionId);
        else if (explorations.insertIfAbsent(actor, sessionId, LocalDateTime.now(clock)) > 0) {
            // 대상을 해시태그로 남겨 target_type 값이 늘지 않는다. 떠오르는 해시태그는 사람 · 태그 · 날짜마다 1 로 센다
            targets.cardTags(card).forEach(tag ->
                    activity.record(actor, TargetType.HASHTAG, tag.hashtagId(), ActivityKind.EXPLORE, List.of(tag)));
        }
        var count = count(sessionId, actor);
        return new ExploringView(count.exploringCount(), count.exploringByMe());
    }

    public BookmarkView bookmark(Long actor, Long sessionId, boolean bookmarked) {
        var owner = cards.findById(sessionId).map(CommunityCardRow::getOwnerId).orElseThrow(CommunityErrors::missing);
        locks.acquire(actor, List.of(owner));
        var card = visible(sessionId, actor);
        if (bookmarked) bookmarks.saveCard(actor, card);
        else bookmarks.deleteCard(actor, sessionId);
        var count = count(sessionId, actor);
        return new BookmarkView(count.bookmarkCount(), count.bookmarkId());
    }

    private CommunityCard visible(Long sessionId, Long viewerId) {
        return reader.card(sessionId, viewerId).orElseThrow(CommunityErrors::missing);
    }

    private CardCounts.Count count(Long sessionId, Long viewerId) {
        return counts.counts(List.of(sessionId), viewerId).getOrDefault(sessionId, CardCounts.Count.ZERO);
    }

    public record LikeView(long likeCount, boolean likedByMe) { }
    public record ExploringView(long exploringCount, boolean exploringByMe) { }
    public record BookmarkView(long bookmarkCount, Long bookmarkId) { }
}
