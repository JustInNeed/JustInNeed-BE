package com.justinneed.community.reaction;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.BookmarkCommandService;
import com.justinneed.community.card.CardCounts;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.community.card.CommunityCardRow;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 카드(세션) 단위 좋아요 · 북마크. 두 동작 모두 지금 공개된 카드에만 되고, 취소도 같다 (아니면 404). 응답 숫자는 카드 응답과 같은 CardCounts 로 읽는다.
 * 카드가 공개에서 빠져도 행은 남아 다시 공개하면 숫자가 돌아온다.
 *
 * <pre>
 * 좋아요 = on conflict 로 넣어 잠금이 없다. 내 카드는 400 (프로필 좋아요와 같다)
 * 북마크 = 스냅샷이 공개 범위 · 비공개 태그를 읽어서 기존 저장처럼 누른 사람과 작성자를 잠근다
 * </pre>
 */
@Service
@Transactional
public class CardReactionService {
    private final CommunityCardReader reader;
    private final CommunityCardRepository cards;
    private final CardCounts counts;
    private final MindmapLikeRepository likes;
    private final BookmarkCommandService bookmarks;
    private final InteractionLocks locks;
    private final Clock clock;

    public CardReactionService(
            CommunityCardReader reader,
            CommunityCardRepository cards,
            CardCounts counts,
            MindmapLikeRepository likes,
            BookmarkCommandService bookmarks,
            InteractionLocks locks,
            Clock clock) {
        this.reader = reader;
        this.cards = cards;
        this.counts = counts;
        this.likes = likes;
        this.bookmarks = bookmarks;
        this.locks = locks;
        this.clock = clock;
    }

    public LikeView like(Long actor, Long sessionId, boolean liked) {
        var card = visible(sessionId, actor);
        if (actor.equals(card.owner().userId()))
            throw new CustomException(ErrorCode.SELF_LIKE_NOT_ALLOWED);
        if (liked) likes.insertIfAbsent(actor, sessionId, LocalDateTime.now(clock));
        else likes.deletePair(actor, sessionId);
        var count = count(sessionId, actor);
        return new LikeView(count.likeCount(), count.likedByMe());
    }

    public BookmarkView bookmark(Long actor, Long sessionId, boolean bookmarked) {
        var owner =
                cards.findById(sessionId)
                        .map(CommunityCardRow::getOwnerId)
                        .orElseThrow(CommunityErrors::missing);
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
        return counts.counts(List.of(sessionId), viewerId)
                .getOrDefault(sessionId, CardCounts.Count.ZERO);
    }

    @Schema(name = "CardReactionServiceLikeView")
    public record LikeView(long likeCount, boolean likedByMe) {}

    public record BookmarkView(long bookmarkCount, @Nullable Long bookmarkId) {}
}
