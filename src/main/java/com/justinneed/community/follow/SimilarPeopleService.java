package com.justinneed.community.follow;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.catalog.CommunityTags;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SimilarPeopleService {
    private static final int MAX_LIMIT = 20;
    private static final Comparator<Match> RANKING = Comparator.<Match>comparingInt(m -> m.hashtags().size()).reversed()
            .thenComparing(Match::lastActivityAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(m -> m.person().userId);

    private final SimilarPeopleRepository people;
    private final CommunityFollowRepository follows;
    public SimilarPeopleService(SimilarPeopleRepository people, CommunityFollowRepository follows) {
        this.people = people; this.follows = follows;
    }
    public SimilarPeople similar(Long viewer, int limit) {
        if (limit < 1 || limit > MAX_LIMIT) throw CommunityErrors.invalid("limit 은 1~" + MAX_LIMIT + " 사이여야 합니다.");
        var persons = publicTags();
        var me = persons.remove(viewer);
        if (me == null) return new SimilarPeople(List.of());
        var ranked = persons.values().stream().map(p -> p.matchWith(me.tags.keySet()))
                .filter(m -> !m.hashtags().isEmpty()).sorted(RANKING).limit(limit).toList();
        if (ranked.isEmpty()) return new SimilarPeople(List.of());
        var followed = new HashSet<>(follows.findFollowedTargets(viewer,
                ranked.stream().map(m -> m.person().userId).toList()));
        return new SimilarPeople(ranked.stream().map(m -> new Person(m.person().userId, m.person().nickname,
                m.hashtags(), followed.contains(m.person().userId))).toList());
    }
    private Map<Long, PublicTags> publicTags() {
        var persons = new HashMap<Long, PublicTags>();
        // 프로필 먼저: 세션 태그를 넣을 때 비공개 해시태그를 이미 알고 있어야 한다
        for (var row : people.findProfileRows()) {
            var person = persons.computeIfAbsent(row.userId(), id -> new PublicTags(id, row.nickname()));
            row.hiddenHashtags().forEach(tag -> person.hidden.add(CommunityTags.key(tag)));
            row.interests().forEach(person::add);
        }
        for (var row : people.findPublicSessionRows()) {
            var person = persons.computeIfAbsent(row.userId(), id -> new PublicTags(id, row.nickname()));
            // 비공개 태그만 달린 세션이어도 공개 세션이므로 활동 시각에는 들어간다
            if (person.lastActivityAt == null || row.activityAt().isAfter(person.lastActivityAt)) {
                person.lastActivityAt = row.activityAt();
            }
            row.tags().forEach(person::add);
        }
        return persons;
    }

    private static final class PublicTags {
        private final Long userId;
        private final String nickname;
        private final Set<String> hidden = new HashSet<>();
        private final SortedMap<String, String> tags = new TreeMap<>();
        private LocalDateTime lastActivityAt;
        private PublicTags(Long userId, String nickname) { this.userId = userId; this.nickname = nickname; }
        private void add(String tag) {
            var key = CommunityTags.key(tag);
            if (!hidden.contains(key)) tags.putIfAbsent(key, tag);
        }
        private Match matchWith(Set<String> keys) {
            return new Match(this, tags.entrySet().stream().filter(tag -> keys.contains(tag.getKey()))
                    .map(Map.Entry::getValue).toList(), lastActivityAt);
        }
    }
    private record Match(PublicTags person, List<String> hashtags, LocalDateTime lastActivityAt) { }
    public record Person(Long userId, String nickname, List<String> matchedHashtags, boolean following) { }
    public record SimilarPeople(List<Person> people) { }
}
