package com.justinneed.community;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.activity.*;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.community.recommend.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

@Import(RecommendationTest.TimeConfig.class)
class RecommendationTest extends CommunityTestSupport {
    static final Instant NOW = Instant.parse("2026-09-30T03:00:00Z");
    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @Autowired RecommendationService recommendations;
    @Autowired TrendingService trending;
    @Autowired PublicGraphService graphs;
    @Autowired BookmarkCommandService bookmarks;
    @Autowired CommunityActivityRepository activity;

    @Test
    void interestAndRecentBookmarkRecommendationsExcludeOwnAndPrivateGraphs() {
        var publicSession = session(ownerId, true, "Java", "Spring");
        session(viewerId, true, "Java");
        var viewer = new CommunityProfile(viewerId);
        viewer.replaceInterests(List.of("java"));
        profiles.saveAndFlush(viewer);
        var result = recommendations.interests(viewerId, 3);
        assertThat(result.basisHashtags()).containsExactly("java");
        assertThat(result.mindmaps()).hasSize(1);
        assertThat(result.mindmaps().get(0).ownerId()).isEqualTo(ownerId);
        assertThat(result.mindmaps().get(0).relatedUrls()).containsExactly("https://example.com");
        assertThat(recommendations.recent(viewerId, 3).mindmaps()).isEmpty();
        var tag = graphs.graph(ownerId, null).hashtags().get(0).hashtagId();
        bookmarks.save(viewerId, TargetType.HASHTAG, List.of(tag), SaveType.HASHTAG_BUNDLE, null);
        assertThat(recommendations.recent(viewerId, 3).mindmaps()).hasSize(1);
        assertThat(recommendations.recent(ownerId, 3).basisHashtags()).isEmpty();
        publicSession.update(null, null, false, null, null, null);
        assertThat(recommendations.interests(viewerId, 3).mindmaps()).isEmpty();
        assertThat(recommendations.recent(viewerId, 3).mindmaps()).isEmpty();
    }

    @Test
    void hiddenInterestsWithoutPublicLeavesDoNotProduceRecommendations() {
        var owner = new CommunityProfile(ownerId);
        owner.replaceInterests(List.of("Java"));
        owner = profiles.saveAndFlush(owner);
        var viewer = new CommunityProfile(viewerId);
        viewer.replaceInterests(List.of("Java"));
        profiles.saveAndFlush(viewer);
        session(ownerId, true, "Unrelated");
        assertThat(recommendations.interests(viewerId, 3).mindmaps()).isEmpty();
        session(ownerId, true, "Java", "Secret");
        owner.replaceHiddenHashtags(List.of("Secret"));
        assertThat(recommendations.interests(viewerId, 3).mindmaps()).isEmpty();
    }

    @Test
    void trendBoundariesUseKoreanMidnightMondayAndExcludeFutureEvents() {
        session(ownerId, true, "Java");
        var graph = graphs.graph(ownerId, null);
        Long tag = graph.hashtags().get(0).hashtagId();
        Long node = graph.nodes().get(0).nodeId();
        event(viewerId, TargetType.NODE, node, tag, ActivityKind.EXPLORE, "2026-09-29T15:00:00Z");
        event(viewerId, TargetType.HASHTAG, tag, tag, ActivityKind.EXPLORE, "2026-09-30T01:00:00Z");
        event(ownerId, TargetType.NODE, node, tag, ActivityKind.BOOKMARK, "2026-09-29T14:59:59Z");
        event(viewerId, TargetType.NODE, node, tag, ActivityKind.EXPLORE, "2026-09-27T15:00:00Z");
        event(ownerId, TargetType.NODE, node, tag, ActivityKind.EXPLORE, "2026-09-27T14:59:59Z");
        event(ownerId, TargetType.HASHTAG, tag, tag, ActivityKind.EXPLORE, "2026-10-01T03:00:00Z");
        var today = trending.trending(TrendingService.Period.TODAY, 10).hashtags().get(0);
        assertThat(today.exploreCount()).isEqualTo(1);
        assertThat(today.bookmarkCount()).isZero();
        var week = trending.trending(TrendingService.Period.WEEK, 10).hashtags().get(0);
        assertThat(week.exploreCount()).isEqualTo(2);
        assertThat(week.bookmarkCount()).isEqualTo(1);
        assertThat(week.rank()).isEqualTo(1);
    }

    @Test
    void trendingRevalidatesRevokedSourcesAndRemovedTagMembership() {
        var s = session(ownerId, true, "Java");
        var graph = graphs.graph(ownerId, null);
        var tag = graph.hashtags().get(0).hashtagId();
        var node = graph.nodes().get(0).nodeId();
        event(viewerId, TargetType.NODE, node, tag, ActivityKind.EXPLORE, NOW.toString());
        assertThat(trending.trending(TrendingService.Period.TODAY, 10).hashtags()).hasSize(1);
        s.update(null, null, false, null, null, null);
        assertThat(trending.trending(TrendingService.Period.TODAY, 10).hashtags()).isEmpty();
        s.update(null, null, true, null, List.of("Spring"), null);
        assertThat(trending.trending(TrendingService.Period.TODAY, 10).hashtags()).isEmpty();
    }

    @Test
    void recommendationEndpointsValidateAndRequireJwt() throws Exception {
        mvc.perform(get("/community/recommendations/mindmaps/interests")).andExpect(status().isUnauthorized());
        mvc.perform(get("/community/recommendations/mindmaps/interests").header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mindmaps").isEmpty());
        mvc.perform(get("/community/recommendations/mindmaps/recent-bookmarks").param("limit", "0")
                .header("Authorization", auth(viewerId))).andExpect(status().isBadRequest());
        mvc.perform(get("/community/recommendations/hashtags/trending").param("period", "MONTH")
                .header("Authorization", auth(viewerId))).andExpect(status().isBadRequest());
        mvc.perform(get("/community/recommendations/hashtags/trending").param("limit", "101")
                .header("Authorization", auth(viewerId))).andExpect(status().isBadRequest());
    }

    private void event(Long actor, TargetType type, Long target, Long tag, ActivityKind kind, String time) {
        var instant = Instant.parse(time);
        activity.saveAndFlush(new CommunityActivity(actor, type, target, tag, kind, instant,
                instant.atZone(ActivityRecorder.ZONE).toLocalDate()));
    }
}
