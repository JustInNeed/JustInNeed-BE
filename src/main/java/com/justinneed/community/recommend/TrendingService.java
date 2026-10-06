package com.justinneed.community.recommend;

import com.justinneed.community.activity.*;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.reaction.TargetResolver;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrendingService {
    public enum Period { TODAY, WEEK }
    private final CommunityActivityRepository activities;
    private final TargetResolver targets;
    private final Clock clock;
    public TrendingService(CommunityActivityRepository activities, TargetResolver targets, Clock clock) {
        this.activities = activities; this.targets = targets; this.clock = clock;
    }
    public Trends trending(Period period, int limit) {
        RecommendationService.checkLimit(limit);
        Instant now = clock.instant();
        var day = now.atZone(ActivityRecorder.ZONE).toLocalDate();
        var first = period == Period.TODAY ? day : day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant start = first.atStartOfDay(ActivityRecorder.ZONE).toInstant();
        var resolved = new HashMap<Target, Optional<TargetResolver.Resolved>>();
        var groups = new HashMap<String, Counts>();
        for (var event : activities.findByOccurredAtGreaterThanEqualAndOccurredAtLessThanEqual(start, now)) {
            var target = resolved.computeIfAbsent(new Target(event.getTargetType(), event.getTargetId()), this::visible);
            if (target.isEmpty()) continue;
            var tag = target.get().tags().stream().filter(t -> t.hashtagId().equals(event.getTagId())).findFirst();
            if (tag.isEmpty()) continue;
            var value = tag.get();
            var count = groups.computeIfAbsent(CommunityTags.key(value.name()), ignored -> new Counts(value));
            if (value.hashtagId() < count.tag.hashtagId()) count.tag = value;
            // Count one person per tag/kind/day, even across different nodes or profiles.
            String key = event.getActorId() + ":" + event.getActivityDay();
            (event.getKind() == ActivityKind.EXPLORE ? count.explorers : count.bookmarkers).add(key);
        }
        var ranked = groups.values().stream().sorted(Comparator.comparingLong(Counts::total).reversed()
                .thenComparing(c -> CommunityTags.key(c.tag.name())).thenComparing(c -> c.tag.hashtagId()))
                .limit(limit).toList();
        var result = new ArrayList<Trend>();
        for (var c : ranked) result.add(new Trend(result.size() + 1, c.tag.hashtagId(), c.tag.name(),
                c.explorers.size(), c.bookmarkers.size()));
        return new Trends(200, period, List.copyOf(result));
    }
    private Optional<TargetResolver.Resolved> visible(Target key) {
        try { return Optional.of(targets.resolve(key.type(), key.id())); }
        catch (CustomException error) {
            if (error.getErrorCode() == ErrorCode.MEMBER_NOT_FOUND) return Optional.empty();
            throw error;
        }
        catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == 404) return Optional.empty();
            throw error;
        }
    }
    private record Target(TargetType type, Long id) { }
    private static class Counts {
        BookmarkSnapshot.Tag tag;
        final Set<String> explorers = new HashSet<>();
        final Set<String> bookmarkers = new HashSet<>();
        Counts(BookmarkSnapshot.Tag tag) { this.tag = tag; }
        long total() { return (long) explorers.size() + bookmarkers.size(); }
    }
    public record Trend(int rank, Long hashtagId, String name, long exploreCount, long bookmarkCount) { }
    public record Trends(int code, Period period, List<Trend> hashtags) { }
}
