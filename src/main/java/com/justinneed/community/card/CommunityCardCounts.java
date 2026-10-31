package com.justinneed.community.card;

import com.justinneed.community.bookmark.CommunityBookmarkRepository;
import com.justinneed.community.reaction.MindmapExplorationRepository;
import com.justinneed.community.reaction.MindmapLikeRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// 카드가 몇 장이든 숫자 3번 + 내 상태 3번만 조회한다
@Component
class CommunityCardCounts implements CardCounts {
    private final MindmapLikeRepository likes;
    private final MindmapExplorationRepository explorations;
    private final CommunityBookmarkRepository bookmarks;

    CommunityCardCounts(MindmapLikeRepository likes, MindmapExplorationRepository explorations,
            CommunityBookmarkRepository bookmarks) {
        this.likes = likes;
        this.explorations = explorations;
        this.bookmarks = bookmarks;
    }

    @Override
    public Map<Long, Count> counts(Collection<Long> sessionIds, Long viewerId) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> likeCounts = totals(likes.countBySessionIds(sessionIds));
        Map<Long, Long> exploringCounts = totals(explorations.countBySessionIds(sessionIds));
        Map<Long, Long> bookmarkCounts = totals(bookmarks.countBySessionIds(sessionIds));
        Set<Long> liked = new HashSet<>();
        Set<Long> exploring = new HashSet<>();
        Map<Long, Long> bookmarkIds = new HashMap<>();
        if (viewerId != null) {
            liked.addAll(likes.findLikedSessions(viewerId, sessionIds));
            exploring.addAll(explorations.findExploringSessions(viewerId, sessionIds));
            bookmarks.findCardBookmarks(viewerId, sessionIds)
                    .forEach(mine -> bookmarkIds.put(mine.getSessionId(), mine.getBookmarkId()));
        }
        Map<Long, Count> counts = new HashMap<>();
        for (Long id : Set.copyOf(sessionIds)) {
            counts.put(id, new Count(likeCounts.getOrDefault(id, 0L), liked.contains(id),
                    bookmarkCounts.getOrDefault(id, 0L), bookmarkIds.get(id),
                    exploringCounts.getOrDefault(id, 0L), exploring.contains(id)));
        }
        return counts;
    }

    private static Map<Long, Long> totals(List<Total> rows) {
        return rows.stream().collect(Collectors.toMap(Total::getSessionId, Total::getTotal));
    }
}
