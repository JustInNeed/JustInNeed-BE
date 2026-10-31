package com.justinneed.community.search;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 프로필 검색 순서. 걸린 카드 많은 순 → 그 카드의 최근 활동순 → id 순.
 * 걸린 카드는 카드 검색이 태그 · 제목으로 돌려주는 카드다. 닉네임으로만 걸린 회원은 0장이라 뒤로 가고,
 * 그 안에서는 공개 카드 전체의 최근 활동으로 선다. 제목으로만 걸린 회원은 결과에 나오지 않는다.
 */
final class ProfileRanking {
    private static final Comparator<Entry> ORDER = Comparator.comparingLong((Entry entry) -> entry.matchingCards).reversed()
            .thenComparing(Entry::recentActivity, Comparator.reverseOrder())
            .thenComparing(entry -> entry.ownerId);

    private final Map<Long, Entry> entries = new LinkedHashMap<>();

    /** 카드 한 장. 같은 회원의 카드를 여러 번 받아 쌓는다. */
    void add(Long ownerId, LocalDateTime activity, boolean matchedNickname, boolean matchedTag, boolean matchedTitle) {
        Entry entry = entries.computeIfAbsent(ownerId, Entry::new);
        entry.listed |= matchedNickname || matchedTag;
        entry.latestCard = latest(entry.latestCard, activity);
        if (matchedTag || matchedTitle) {
            entry.matchingCards++;
            entry.latestMatchingCard = latest(entry.latestMatchingCard, activity);
        }
    }

    List<Long> ownerIds() {
        return entries.values().stream().filter(entry -> entry.listed).sorted(ORDER).map(entry -> entry.ownerId).toList();
    }

    private static LocalDateTime latest(LocalDateTime current, LocalDateTime activity) {
        return current == null || activity.isAfter(current) ? activity : current;
    }

    private static final class Entry {
        private final Long ownerId;
        private boolean listed;
        private long matchingCards;
        private LocalDateTime latestCard;
        private LocalDateTime latestMatchingCard;

        Entry(Long ownerId) {
            this.ownerId = ownerId;
        }

        LocalDateTime recentActivity() {
            return matchingCards > 0 ? latestMatchingCard : latestCard;
        }
    }
}
