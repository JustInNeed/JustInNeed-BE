package com.justinneed.community;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.jayway.jsonpath.JsonPath;
import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.community.activity.*;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.graph.*;
import com.justinneed.community.reaction.InteractionService;
import com.justinneed.session.management.domain.Source;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;

/** 떠오르는 해시태그의 기간 · 증감 · 집계 규칙. 시계를 KST 와 UTC 날짜가 갈리는 시각에 둔다. */
@Import(TrendingTest.TimeConfig.class)
class TrendingTest extends CommunityTestSupport {
    // 제품 코드의 상수를 쓰면 상수를 바꿔도 테스트가 같이 움직인다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String TRENDING = "/community/recommendations/hashtags/trending";

    static final class MovableClock extends Clock {
        private Instant now = Instant.EPOCH;
        void set(String time) { now = Instant.parse(time); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary MovableClock movableClock() { return new MovableClock(); }
    }

    @Autowired MovableClock clock;
    @Autowired PublicGraphService graphs;
    @Autowired CommunityNodeRepository nodes;
    @Autowired CommunityActivityRepository activity;
    @Autowired InteractionService interactions;
    @Autowired BookmarkCommandService bookmarks;
    private Long tag;
    private Long node;

    @BeforeEach
    void publicSession() {
        clock.set("2026-09-30T03:00:00Z");
        session(ownerId, true, "Java");
        var graph = graphs.graph(ownerId, null);
        tag = graph.hashtags().get(0).hashtagId();
        node = graph.nodes().get(0).nodeId();
    }

    // 2026-10-01(목) 01:00 KST. UTC 로는 아직 9월 30일이다
    @Test
    void todayAndYesterdayFollowKoreanMidnightWhenUtcDateDiffers() {
        clock.set("2026-09-30T16:00:00Z");
        explore(member(), "2026-09-30T15:00:00Z");    // 오늘 00:00 KST
        explore(member(), "2026-09-29T15:59:59Z");    // 어제 00:59:59 KST
        explore(member(), "2026-09-29T15:00:00Z");    // 어제 00:00 KST
        explore(member(), "2026-09-29T14:59:59Z");    // 그제 (어느 기간도 아니다)

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, -1));
    }

    // 하루가 덜 지났을 때 어제 하루 전체와 비교하면 증감이 음수로 치우친다
    @Test
    void todayComparesWithYesterdayUpToTheSameTime() {
        clock.set("2026-09-30T16:00:00Z");            // 10월 1일 01:00 KST
        explore(member(), "2026-09-30T15:30:00Z");    // 오늘 00:30 KST
        explore(member(), "2026-09-29T15:30:00Z");    // 어제 00:30 KST
        explore(member(), "2026-09-29T16:00:01Z");    // 어제 01:00:01 KST (같은 시각을 넘었다)
        explore(member(), "2026-09-30T14:00:00Z");    // 어제 23:00 KST

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 0));
    }

    // 달력 주가 아니라 최근 7일이라, 언제 봐도 두 기간의 길이가 같다
    @Test
    void weekIsLastSevenDaysComparedWithTheSevenDaysBefore() {
        clock.set("2026-10-04T16:00:00Z");
        explore(member(), "2026-09-27T16:00:00Z");    // 최근 7일의 시작
        explore(member(), "2026-09-27T15:59:59Z");    // 직전 7일의 끝
        explore(member(), "2026-09-20T16:00:00Z");    // 직전 7일의 시작
        explore(member(), "2026-09-20T15:59:59Z");    // 어느 기간도 아니다

        assertThat(row("week", "Java")).isEqualTo(new Row(1, 1, -1));
    }

    @Test
    void futureEventsAreIgnored() {
        explore(member(), "2026-09-30T02:59:59Z");
        explore(member(), "2026-09-30T03:00:00Z");
        explore(member(), "2026-09-30T03:00:01Z");

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 2));
    }

    @Test
    void recordedDayIsTheKoreanDay() {
        var actor = member();
        clock.set("2026-09-30T14:30:00Z");            // 9월 30일 23:30 KST
        interactions.exploring(actor, TargetType.NODE, node, true);
        interactions.exploring(actor, TargetType.NODE, node, false);
        clock.set("2026-09-30T15:30:00Z");            // 10월 1일 00:30 KST, UTC 는 같은 날
        interactions.exploring(actor, TargetType.NODE, node, true);
        interactions.exploring(actor, TargetType.NODE, node, false);
        clock.set("2026-10-01T00:30:00Z");            // 10월 1일 09:30 KST, UTC 는 다음 날
        interactions.exploring(actor, TargetType.NODE, node, true);

        assertThat(activity.findAll()).extracting(CommunityActivity::getActivityDay)
                .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1));
    }

    @Test
    void trendIsCountMinusPreviousAndEqualsCountWhenAbsentBefore() {
        session(ownerId, true, "Rising", "Falling", "Steady");
        var tags = tagIds(ownerId);
        var today = "2026-09-30T01:00:00Z";
        var yesterday = "2026-09-29T01:00:00Z";
        explore(member(), tags.get("rising"), today);
        explore(member(), tags.get("rising"), today);
        explore(member(), tags.get("falling"), today);
        explore(member(), tags.get("falling"), yesterday);
        explore(member(), tags.get("falling"), yesterday);
        explore(member(), tags.get("steady"), today);
        explore(member(), tags.get("steady"), yesterday);
        explore(member(), tags.get("java"), yesterday);    // 어제만 있으면 오늘 순위엔 없다

        assertThat(names("today")).containsExactly("Rising", "Falling", "Steady");
        assertThat(row("today", "Rising")).isEqualTo(new Row(1, 2, 2));
        assertThat(row("today", "Falling")).isEqualTo(new Row(2, 1, -1));
        assertThat(row("today", "Steady")).isEqualTo(new Row(3, 1, 0));
    }

    @Test
    void withdrawnActorsAreLeftOutOfBothWindowsAndTheirRowsStay() throws Exception {
        var gone = member();
        var stays = member();
        explore(gone, "2026-09-30T01:00:00Z");
        explore(stays, "2026-09-30T01:00:00Z");
        explore(gone, "2026-09-29T01:00:00Z");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 1));

        members.findById(gone).orElseThrow().withdraw("test", LocalDateTime.of(2026, 9, 30, 12, 0));

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));
        assertThat(activity.count()).isEqualTo(3);
    }

    @Test
    void myOwnActionsCount() {
        interactions.exploring(ownerId, TargetType.HASHTAG, tag, true);
        bookmarks.save(ownerId, TargetType.HASHTAG, List.of(tag), SaveType.HASHTAG_BUNDLE, null);

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 2));
    }

    @Test
    void undoKeepsTheCountAndRepeatingOnTheSameDayDoesNotAddOne() {
        var actor = member();
        interactions.exploring(actor, TargetType.NODE, node, true);
        interactions.exploring(actor, TargetType.NODE, node, false);
        interactions.exploring(actor, TargetType.NODE, node, true);
        var saved = bookmarks.save(actor, TargetType.HASHTAG, List.of(tag), SaveType.HASHTAG_BUNDLE, null);
        bookmarks.delete(actor, saved.bookmarkId());

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 2));
    }

    @Test
    void onePersonCountsOncePerTagKindAndDayAcrossTargetsAndOwners() {
        var other = member();
        session(other, true, "JAVA");
        var otherGraph = graphs.graph(other, null);
        var actor = member();
        explore(actor, node, tag, TargetType.NODE, "2026-09-30T01:00:00Z");
        explore(actor, tag, tag, TargetType.HASHTAG, "2026-09-30T02:00:00Z");
        explore(actor, otherGraph.nodes().get(0).nodeId(), otherGraph.hashtags().get(0).hashtagId(),
                TargetType.NODE, "2026-09-30T02:30:00Z");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));

        event(actor, TargetType.HASHTAG, tag, tag, ActivityKind.BOOKMARK, "2026-09-30T02:40:00Z");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 2));

        explore(actor, node, tag, TargetType.NODE, "2026-09-29T01:00:00Z");
        assertThat(row("week", "Java")).isEqualTo(new Row(1, 3, 3));
    }

    @Test
    void sameNameAcrossOwnersIsOneHashtagNamedAfterTheOldestAndPreviousCountsJoinIt() {
        var other = member();
        session(other, true, "JAVA");
        var otherTag = graphs.graph(other, null).hashtags().get(0).hashtagId();
        explore(member(), otherTag, "2026-09-30T01:00:00Z");
        explore(member(), tag, "2026-09-30T01:00:00Z");
        explore(member(), otherTag, "2026-09-29T01:00:00Z");

        assertThat(names("today")).containsExactly("Java");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 1));
    }

    @Test
    void equalCountsAreOrderedByName() {
        session(ownerId, true, "banana", "Apple");
        var tags = tagIds(ownerId);
        for (var name : List.of("banana", "apple", "java")) explore(member(), tags.get(name), "2026-09-30T01:00:00Z");

        assertThat(names("today")).containsExactly("Apple", "banana", "Java");
    }

    @Test
    void limitCutsTheRanking() throws Exception {
        session(ownerId, true, "Apple", "Banana");
        tagIds(ownerId).values().forEach(id -> explore(member(), id, "2026-09-30T01:00:00Z"));

        mvc.perform(get(TRENDING).param("limit", "2").header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.hashtags.length()").value(2));
    }

    @Test
    void responseHasOnlyTheContractFields() throws Exception {
        explore(member(), "2026-09-30T01:00:00Z");

        var body = mvc.perform(get(TRENDING).param("period", "week").header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("period", "hashtags");
        Map<String, Object> first = JsonPath.read(body, "$.data.hashtags[0]");
        assertThat(first.keySet()).containsExactlyInAnyOrder("rank", "name", "count", "trend");
    }

    @Test
    void periodIgnoresCaseAndRejectsOtherValues() throws Exception {
        explore(member(), "2026-09-30T01:00:00Z");
        for (var period : List.of("today", "TODAY", "Today", "week", "WEEK", "wEeK", "")) {
            mvc.perform(get(TRENDING).param("period", period).header("Authorization", auth(viewerId)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.hashtags[0].name").value("Java"));
        }
        for (var period : List.of("month", "today ", "오늘")) {
            mvc.perform(get(TRENDING).param("period", period).header("Authorization", auth(viewerId)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void privateSessionTakesItsHistoryOutOfBothWindows() {
        explore(member(), node, tag, TargetType.NODE, "2026-09-30T01:00:00Z");
        explore(member(), "2026-09-29T01:00:00Z");
        var second = session(ownerId, true, "Java");
        var secondNode = graphs.graph(ownerId, null).nodes().stream().filter(n -> !n.nodeId().equals(node))
                .findFirst().orElseThrow().nodeId();
        explore(member(), secondNode, tag, TargetType.NODE, "2026-09-30T01:00:00Z");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 1));

        sessions.findAll().stream().filter(s -> !s.getId().equals(second.getId())).forEach(s -> s.update(null, null, false, null, null));

        // 첫 세션의 노드 활동만 빠진다. 해시태그 활동은 태그가 아직 다른 공개 세션에 있어 남는다
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 0));
    }

    @Test
    void nodeActivityFollowsItsSessionAndTagNotTheSourceList() {
        var s = sessions.findAll().stream().filter(b -> b.getUserId().equals(ownerId)).findFirst().orElseThrow();
        explore(member(), node, tag, TargetType.NODE, "2026-09-30T01:00:00Z");

        s.replaceSources(List.of(new Source("other", "https://example.org/other", "excerpt")));

        assertThat(names("today")).containsExactly("Java");
    }

    @Test
    void tagRemovedFromTheSessionStopsCountingEvenOnAStillVisibleNode() {
        var s = session(ownerId, true, "Java", "Spring");
        var tags = tagIds(ownerId);
        var shared = nodeOf(s.getId());
        explore(member(), shared, tags.get("java"), TargetType.NODE, "2026-09-30T01:00:00Z");
        explore(member(), shared, tags.get("spring"), TargetType.NODE, "2026-09-30T01:00:00Z");
        assertThat(names("today")).containsExactly("Java", "Spring");

        s.update(null, null, null, null, List.of("Java"));

        assertThat(names("today")).containsExactly("Java");
    }

    @Test
    void hashtagNeedsAPublicSessionOrAPublicInterest() {
        var s = sessions.findAll().stream().filter(b -> b.getUserId().equals(ownerId)).findFirst().orElseThrow();
        explore(member(), "2026-09-30T01:00:00Z");
        assertThat(names("today")).containsExactly("Java");

        s.update(null, null, false, null, null);
        assertThat(names("today")).isEmpty();

        var profile = profiles.findById(ownerId).orElseGet(() -> new CommunityProfile(ownerId));
        profile.replaceInterests(List.of("JAVA"));
        profiles.saveAndFlush(profile);
        assertThat(names("today")).containsExactly("Java");

        profile.replaceHiddenHashtags(List.of("java"));
        profiles.saveAndFlush(profile);
        assertThat(names("today")).isEmpty();
    }

    @Test
    void hiddenHashtagLeavesAndTheOtherTagsOfTheSessionStay() {
        var s = session(ownerId, true, "Java", "Spring");
        var tags = tagIds(ownerId);
        var shared = nodeOf(s.getId());
        explore(member(), shared, tags.get("java"), TargetType.NODE, "2026-09-30T01:00:00Z");
        explore(member(), shared, tags.get("spring"), TargetType.NODE, "2026-09-30T01:00:00Z");
        explore(member(), tags.get("spring"), "2026-09-30T01:00:00Z");
        var profile = profiles.findById(ownerId).orElseGet(() -> new CommunityProfile(ownerId));
        profile.replaceHiddenHashtags(List.of("SPRING"));
        profiles.saveAndFlush(profile);

        assertThat(names("today")).containsExactly("Java");
    }

    @Test
    void withdrawnOwnerTakesTheirHashtagsOut() {
        explore(member(), "2026-09-30T01:00:00Z");
        assertThat(names("today")).containsExactly("Java");

        members.findById(ownerId).orElseThrow().withdraw("test", LocalDateTime.of(2026, 9, 30, 12, 0));

        assertThat(names("today")).isEmpty();
    }

    @Test
    void readingDoesNotCreateNodes() {
        var s = sessions.findAll().stream().filter(b -> b.getUserId().equals(ownerId)).findFirst().orElseThrow();
        explore(member(), "2026-09-30T01:00:00Z");
        s.replaceSources(List.of(new Source("a", "https://example.com", "x"), new Source("b", "https://example.org/new", "y")));
        long before = nodes.count();

        assertThat(names("today")).containsExactly("Java");

        assertThat(nodes.count()).isEqualTo(before);
    }

    private record Row(int rank, long count, long trend) { }

    private Row row(String period, String name) {
        try {
            var body = trending(period);
            List<Map<String, Object>> found = JsonPath.read(body, "$.data.hashtags[?(@.name == '" + name + "')]");
            assertThat(found).hasSize(1);
            var hashtag = found.get(0);
            return new Row((int) hashtag.get("rank"), ((Number) hashtag.get("count")).longValue(), ((Number) hashtag.get("trend")).longValue());
        } catch (Exception error) { throw new AssertionError(error); }
    }
    private List<String> names(String period) {
        try { return JsonPath.read(trending(period), "$.data.hashtags[*].name"); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private String trending(String period) throws Exception {
        return mvc.perform(get(TRENDING).param("period", period).header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }
    private Map<String, Long> tagIds(Long owner) {
        var ids = new HashMap<String, Long>();
        graphs.graph(owner, null).hashtags().forEach(t -> ids.put(t.name().toLowerCase(Locale.ROOT), t.hashtagId()));
        return ids;
    }
    private Long nodeOf(Long sessionId) {
        return nodes.findByOwnerId(ownerId).stream().filter(n -> n.getSessionId().equals(sessionId))
                .findFirst().orElseThrow().getId();
    }
    private Long member() {
        return members.saveAndFlush(new Member(SocialProvider.KAKAO, UUID.randomUUID().toString(),
                "T" + UUID.randomUUID().toString().substring(0, 7), null)).getId();
    }
    private void explore(Long actor, String time) { explore(actor, tag, tag, TargetType.HASHTAG, time); }
    private void explore(Long actor, Long hashtag, String time) { explore(actor, hashtag, hashtag, TargetType.HASHTAG, time); }
    private void explore(Long actor, Long target, Long hashtag, TargetType type, String time) {
        event(actor, type, target, hashtag, ActivityKind.EXPLORE, time);
    }
    private void event(Long actor, TargetType type, Long target, Long hashtag, ActivityKind kind, String time) {
        var instant = Instant.parse(time);
        activity.saveAndFlush(new CommunityActivity(actor, type, target, hashtag, kind, instant,
                instant.atZone(KST).toLocalDate()));
    }
}
