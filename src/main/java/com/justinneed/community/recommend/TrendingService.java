package com.justinneed.community.recommend;

import com.justinneed.community.activity.*;
import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.service.CommunitySharing;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 떠오르는 해시태그. 기간은 모두 KST 달력 기준이다.
 *
 * <pre>
 * 오늘     = 오늘 00:00 ~ 지금, 직전 기간은 어제 하루
 * 이번 주  = 이번 주 월요일 00:00 ~ 지금, 직전 기간은 그 앞 7일 (지난주 월 ~ 일)
 * count    = 기간 안의 나도 탐색중 + 북마크. 사람 · 태그 · 종류 · 날짜마다 1 (대상이 여러 개여도, 취소해도 그대로)
 * trend    = count - 직전 기간 count. 같은 규칙으로 세고, 직전 기간에 없던 태그는 count 와 같다
 * </pre>
 *
 * 탈퇴한 사람의 활동은 뺀다. 태그는 지금 공개된 것만 센다 (아래 Shown).
 */
@Service
public class TrendingService {
    public enum Period {
        TODAY(1), WEEK(7);
        private final int days;
        Period(int days) { this.days = days; }
        public static Period parse(String value) {
            return Arrays.stream(values()).filter(p -> p.name().equalsIgnoreCase(value)).findFirst()
                    .orElseThrow(() -> CommunityErrors.invalid("기간은 today 또는 week 만 쓸 수 있습니다"));
        }
    }
    private final CommunityActivityRepository activities;
    private final CommunitySharing sharing;
    private final Clock clock;
    public TrendingService(CommunityActivityRepository activities, CommunitySharing sharing, Clock clock) {
        this.activities = activities; this.sharing = sharing; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public Trends trending(Period period, int limit) {
        RecommendationService.checkLimit(limit);
        Instant now = clock.instant();
        var day = now.atZone(ActivityRecorder.ZONE).toLocalDate();
        var first = period == Period.TODAY ? day : day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant start = first.atStartOfDay(ActivityRecorder.ZONE).toInstant();
        Instant previousStart = first.minusDays(period.days).atStartOfDay(ActivityRecorder.ZONE).toInstant();
        var shown = shown(previousStart, now);
        var groups = new HashMap<String, Counts>();
        for (var event : activities.findTrending(previousStart, now)) {
            if (!shown.includes(event, sharing)) continue;
            var count = groups.computeIfAbsent(CommunityTags.key(event.tagName()), Counts::new);
            // Count one person per tag/kind/day, even across different nodes or profiles.
            var person = new Person(event.actorId(), event.kind(), event.activityDay());
            if (event.occurredAt().isBefore(start)) count.previous.add(person);
            else count.current(person, event);
        }
        var ranked = groups.values().stream().filter(c -> !c.current.isEmpty())
                .sorted(Comparator.comparingInt((Counts c) -> c.current.size()).reversed().thenComparing(c -> c.key))
                .limit(limit).toList();
        var result = new ArrayList<Trend>();
        for (var c : ranked) result.add(new Trend(result.size() + 1, c.name, c.current.size(),
                (long) c.current.size() - c.previous.size()));
        return new Trends(period, List.copyOf(result));
    }
    private Shown shown(Instant start, Instant end) {
        var owners = activities.findTrendingOwners(start, end).stream()
                .collect(Collectors.toMap(CommunityProfile::getUserId, p -> p));
        var bySession = new HashMap<Long, Set<String>>();
        var byOwner = new HashMap<Long, Set<String>>();
        for (var session : activities.findTrendingSessions(start, end)) {
            var tags = session.tags().stream().map(CommunityTags::key).collect(Collectors.toSet());
            bySession.put(session.id(), tags);
            byOwner.computeIfAbsent(session.ownerId(), ignored -> new HashSet<>()).addAll(tags);
        }
        owners.values().forEach(p -> byOwner.computeIfAbsent(p.getUserId(), ignored -> new HashSet<>())
                .addAll(p.getInterests().stream().map(CommunityTags::key).toList()));
        return new Shown(owners, bySession, byOwner);
    }
    /**
     * 지금 공개된 태그. 비공개로 돌린 태그는 그 태그만 빠지고 같은 세션의 다른 태그는 남는다.
     * 해시태그 대상은 주인의 공개 세션 태그 · 관심사에 있어야 하고, 노드 대상은 그 노드의 세션이 공개이고 그 태그가 달려 있어야 한다.
     * 출처 목록은 보지 않는다: 태그는 그대로 공개이고, AI 노드는 출처에 없다.
     */
    private record Shown(Map<Long, CommunityProfile> owners, Map<Long, Set<String>> bySession, Map<Long, Set<String>> byOwner) {
        boolean includes(TrendingActivity event, CommunitySharing sharing) {
            var owner = owners.get(event.ownerId());
            if (owner != null && sharing.isHidden(owner, event.tagName())) return false;
            var tag = CommunityTags.key(event.tagName());
            return switch (event.targetType()) {
                case HASHTAG -> byOwner.getOrDefault(event.ownerId(), Set.of()).contains(tag);
                case NODE -> bySession.getOrDefault(event.sessionId(), Set.of()).contains(tag);
            };
        }
    }
    private record Person(Long actorId, ActivityKind kind, LocalDate day) { }
    private static class Counts {
        final String key;
        final Set<Person> current = new HashSet<>();
        final Set<Person> previous = new HashSet<>();
        String name;
        Long tagId;
        Counts(String key) { this.key = key; }
        // 주인이 달라도 이름이 같으면 한 태그다. 표시는 가장 오래된 행의 이름을 쓴다
        void current(Person person, TrendingActivity event) {
            current.add(person);
            if (tagId == null || event.tagId() < tagId) { tagId = event.tagId(); name = event.tagName(); }
        }
    }
    public record Trend(int rank, String name, long count, long trend) { }
    public record Trends(Period period, List<Trend> hashtags) { }
}
