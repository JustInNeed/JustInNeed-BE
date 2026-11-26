package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.community.activity.ActivityKind;
import com.justinneed.community.activity.CommunityActivity;
import com.justinneed.community.activity.CommunityActivityRepository;
import com.justinneed.community.bookmark.BookmarkCommandService;
import com.justinneed.community.bookmark.SaveType;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.graph.CommunityNodeRepository;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.session.management.domain.Source;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/** 떠오르는 해시태그의 기간 · 증감 · 집계 규칙. 시계를 KST 와 UTC 날짜가 갈리는 시각에 둔다. */
@Import(TrendingTest.TimeConfig.class)
class TrendingTest extends CommunityTestSupport {
    // 제품 코드의 상수를 쓰면 상수를 바꿔도 테스트가 같이 움직인다
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String TRENDING = "/community/recommendations/hashtags/trending";

    static final class MovableClock extends Clock {
        private Instant now = Instant.EPOCH;

        void set(String time) {
            now = Instant.parse(time);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class TimeConfig {
        @Bean
        @Primary
        MovableClock movableClock() {
            return new MovableClock();
        }
    }

    @Autowired MovableClock clock;
    @Autowired PublicGraphService graphs;
    @Autowired CommunityNodeRepository nodes;
    @Autowired CommunityActivityRepository activity;
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
    @DisplayName("[COM-6-3 S2] 오늘 · 어제는 KST 자정 기준이다")
    void todayAndYesterdayFollowKoreanMidnightWhenUtcDateDiffers() {
        clock.set("2026-09-30T16:00:00Z");
        bookmark(member(), "2026-09-30T15:00:00Z"); // 오늘 00:00 KST
        bookmark(member(), "2026-09-29T15:59:59Z"); // 어제 00:59:59 KST
        bookmark(member(), "2026-09-29T15:00:00Z"); // 어제 00:00 KST
        bookmark(member(), "2026-09-29T14:59:59Z"); // 그제 (어느 기간도 아니다)

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, -1));
    }

    // 하루가 덜 지났을 때 어제 하루 전체와 비교하면 증감이 음수로 치우친다
    @Test
    @DisplayName("[COM-6-3 S2] 오늘은 어제 같은 시각까지와 비교한다")
    void todayComparesWithYesterdayUpToTheSameTime() {
        clock.set("2026-09-30T16:00:00Z"); // 10월 1일 01:00 KST
        bookmark(member(), "2026-09-30T15:30:00Z"); // 오늘 00:30 KST
        bookmark(member(), "2026-09-29T15:30:00Z"); // 어제 00:30 KST
        bookmark(member(), "2026-09-29T16:00:01Z"); // 어제 01:00:01 KST (같은 시각을 넘었다)
        bookmark(member(), "2026-09-30T14:00:00Z"); // 어제 23:00 KST

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 0));
    }

    // 달력 주가 아니라 최근 7일이라, 언제 봐도 두 기간의 길이가 같다
    @Test
    @DisplayName("[COM-6-3 S2] 이번 주는 최근 7일을 그 앞 7일과 비교한다")
    void weekIsLastSevenDaysComparedWithTheSevenDaysBefore() {
        clock.set("2026-10-04T16:00:00Z");
        bookmark(member(), "2026-09-27T16:00:00Z"); // 최근 7일의 시작
        bookmark(member(), "2026-09-27T15:59:59Z"); // 직전 7일의 끝
        bookmark(member(), "2026-09-20T16:00:00Z"); // 직전 7일의 시작
        bookmark(member(), "2026-09-20T15:59:59Z"); // 어느 기간도 아니다

        assertThat(row("week", "Java")).isEqualTo(new Row(1, 1, -1));
    }

    @Test
    void futureEventsAreIgnored() {
        bookmark(member(), "2026-09-30T02:59:59Z");
        bookmark(member(), "2026-09-30T03:00:00Z");
        bookmark(member(), "2026-09-30T03:00:01Z");

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 2));
    }

    @Test
    void recordedDayIsTheKoreanDay() {
        var actor = member();
        clock.set("2026-09-30T14:30:00Z"); // 9월 30일 23:30 KST
        bookmarks.delete(actor, saveTag(actor));
        clock.set("2026-09-30T15:30:00Z"); // 10월 1일 00:30 KST, UTC 는 같은 날
        bookmarks.delete(actor, saveTag(actor));
        clock.set("2026-10-01T00:30:00Z"); // 10월 1일 09:30 KST, UTC 는 다음 날
        saveTag(actor);

        assertThat(activity.findAll())
                .extracting(CommunityActivity::getActivityDay)
                .containsExactlyInAnyOrder(LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 1));
    }

    @Test
    void trendIsCountMinusPreviousAndEqualsCountWhenAbsentBefore() {
        session(ownerId, true, "Rising", "Falling", "Steady");
        var tags = tagIds(ownerId);
        var today = "2026-09-30T01:00:00Z";
        var yesterday = "2026-09-29T01:00:00Z";
        bookmark(member(), tags.get("rising"), today);
        bookmark(member(), tags.get("rising"), today);
        bookmark(member(), tags.get("falling"), today);
        bookmark(member(), tags.get("falling"), yesterday);
        bookmark(member(), tags.get("falling"), yesterday);
        bookmark(member(), tags.get("steady"), today);
        bookmark(member(), tags.get("steady"), yesterday);
        bookmark(member(), tags.get("java"), yesterday); // 어제만 있으면 오늘 순위엔 없다

        assertThat(names("today")).containsExactly("Rising", "Falling", "Steady");
        assertThat(row("today", "Rising")).isEqualTo(new Row(1, 2, 2));
        assertThat(row("today", "Falling")).isEqualTo(new Row(2, 1, -1));
        assertThat(row("today", "Steady")).isEqualTo(new Row(3, 1, 0));
    }

    @Test
    @DisplayName("[COM-6-3 R1] 탈퇴 회원의 활동은 두 기간 숫자에서 빠지고 행은 남는다")
    void withdrawnActorsAreLeftOutOfBothWindowsAndTheirRowsStay() throws Exception {
        var gone = member();
        var stays = member();
        bookmark(gone, "2026-09-30T01:00:00Z");
        bookmark(stays, "2026-09-30T01:00:00Z");
        bookmark(gone, "2026-09-29T01:00:00Z");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 1));

        members.findById(gone).orElseThrow().withdraw("test", LocalDateTime.of(2026, 9, 30, 12, 0));

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));
        assertThat(activity.count()).isEqualTo(3);
    }

    @Test
    void myOwnActionsCount() {
        saveTag(ownerId);

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));
    }

    @Test
    void undoKeepsTheCountAndRepeatingOnTheSameDayDoesNotAddOne() {
        var actor = member();
        bookmarks.delete(actor, saveTag(actor));
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));

        saveTag(actor);

        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));
    }

    @Test
    @DisplayName("[COM-6-3 S3] 한 사람 · 태그 · 날짜마다 1 로 센다")
    void onePersonCountsOncePerTagAndDayAcrossTargetsAndOwners() {
        var other = member();
        session(other, true, "JAVA");
        var otherGraph = graphs.graph(other, null);
        var actor = member();
        bookmark(actor, node, tag, TargetType.NODE, "2026-09-30T01:00:00Z");
        bookmark(actor, tag, tag, TargetType.HASHTAG, "2026-09-30T02:00:00Z");
        bookmark(
                actor,
                otherGraph.nodes().get(0).nodeId(),
                otherGraph.hashtags().get(0).hashtagId(),
                TargetType.NODE,
                "2026-09-30T02:30:00Z");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));

        bookmark(actor, node, tag, TargetType.NODE, "2026-09-29T01:00:00Z");
        assertThat(row("week", "Java")).isEqualTo(new Row(1, 2, 2));
    }

    // 없앤 "나도 탐색중" 이 남긴 예전 EXPLORE 행은 두 기간 어디에서도 세지 않는다
    @Test
    @DisplayName("[COM-6-3 S3] 예전 나도 탐색중 활동은 세지 않고 북마크만 센다")
    void legacyExploreRowsAreNotCounted() {
        session(ownerId, true, "Legacy");
        var legacy = tagIds(ownerId).get("legacy");
        var actor = member();
        event(actor, TargetType.HASHTAG, tag, tag, ActivityKind.EXPLORE, "2026-09-30T01:00:00Z");
        event(member(), TargetType.HASHTAG, tag, tag, ActivityKind.EXPLORE, "2026-09-30T01:00:00Z");
        event(member(), TargetType.HASHTAG, tag, tag, ActivityKind.EXPLORE, "2026-09-29T01:00:00Z");
        event(
                member(),
                TargetType.HASHTAG,
                legacy,
                legacy,
                ActivityKind.EXPLORE,
                "2026-09-30T01:00:00Z");
        bookmark(actor, "2026-09-30T02:00:00Z");

        assertThat(names("today")).containsExactly("Java");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 1));
        assertThat(names("week")).containsExactly("Java");
        assertThat(row("week", "Java")).isEqualTo(new Row(1, 1, 1));
        assertThat(activity.count()).isEqualTo(5);
    }

    @Test
    void sameNameAcrossOwnersIsOneHashtagNamedAfterTheOldestAndPreviousCountsJoinIt() {
        var other = member();
        session(other, true, "JAVA");
        var otherTag = graphs.graph(other, null).hashtags().get(0).hashtagId();
        bookmark(member(), otherTag, "2026-09-30T01:00:00Z");
        bookmark(member(), tag, "2026-09-30T01:00:00Z");
        bookmark(member(), otherTag, "2026-09-29T01:00:00Z");

        assertThat(names("today")).containsExactly("Java");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 1));
    }

    @Test
    @DisplayName("[COM-6-3 S3] 개수가 같으면 태그 이름 순이다")
    void equalCountsAreOrderedByName() {
        session(ownerId, true, "banana", "Apple");
        var tags = tagIds(ownerId);
        for (var name : List.of("banana", "apple", "java"))
            bookmark(member(), tags.get(name), "2026-09-30T01:00:00Z");

        assertThat(names("today")).containsExactly("Apple", "banana", "Java");
    }

    @Test
    void limitCutsTheRanking() throws Exception {
        session(ownerId, true, "Apple", "Banana");
        tagIds(ownerId).values().forEach(id -> bookmark(member(), id, "2026-09-30T01:00:00Z"));

        mvc.perform(get(TRENDING).param("limit", "2").header("Authorization", auth(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hashtags.length()").value(2));
    }

    @Test
    void responseHasOnlyTheContractFields() throws Exception {
        bookmark(member(), "2026-09-30T01:00:00Z");

        var body =
                mvc.perform(
                                get(TRENDING)
                                        .param("period", "week")
                                        .header("Authorization", auth(viewerId)))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        Map<String, Object> data = JsonPath.read(body, "$.data");
        assertThat(data.keySet()).containsExactlyInAnyOrder("period", "hashtags");
        Map<String, Object> first = JsonPath.read(body, "$.data.hashtags[0]");
        assertThat(first.keySet()).containsExactlyInAnyOrder("rank", "name", "count", "trend");
    }

    @Test
    @DisplayName("[COM-6-3 S6] 기간은 today · week 를 대소문자 무시로 받고 그 밖은 400")
    void periodIgnoresCaseAndRejectsOtherValues() throws Exception {
        bookmark(member(), "2026-09-30T01:00:00Z");
        for (var period : List.of("today", "TODAY", "Today", "week", "WEEK", "wEeK", "")) {
            mvc.perform(
                            get(TRENDING)
                                    .param("period", period)
                                    .header("Authorization", auth(viewerId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.hashtags[0].name").value("Java"));
        }
        for (var period : List.of("month", "today ", "오늘")) {
            mvc.perform(
                            get(TRENDING)
                                    .param("period", period)
                                    .header("Authorization", auth(viewerId)))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void privateSessionTakesItsHistoryOutOfBothWindows() {
        bookmark(member(), node, tag, TargetType.NODE, "2026-09-30T01:00:00Z");
        bookmark(member(), "2026-09-29T01:00:00Z");
        var second = session(ownerId, true, "Java");
        var secondNode =
                graphs.graph(ownerId, null).nodes().stream()
                        .filter(n -> !n.nodeId().equals(node))
                        .findFirst()
                        .orElseThrow()
                        .nodeId();
        bookmark(member(), secondNode, tag, TargetType.NODE, "2026-09-30T01:00:00Z");
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 2, 1));

        sessions.findAll().stream()
                .filter(s -> !s.getId().equals(second.getId()))
                .forEach(s -> s.update(null, null, false, null, null));

        // 첫 세션의 노드 활동만 빠진다. 해시태그 활동은 태그가 아직 다른 공개 세션에 있어 남는다
        assertThat(row("today", "Java")).isEqualTo(new Row(1, 1, 0));
    }

    @Test
    void nodeActivityFollowsItsSessionAndTagNotTheSourceList() {
        var s =
                sessions.findAll().stream()
                        .filter(b -> b.getUserId().equals(ownerId))
                        .findFirst()
                        .orElseThrow();
        bookmark(member(), node, tag, TargetType.NODE, "2026-09-30T01:00:00Z");

        s.replaceSources(List.of(new Source("other", "https://example.org/other", "excerpt")));

        assertThat(names("today")).containsExactly("Java");
    }

    @Test
    void tagRemovedFromTheSessionStopsCountingEvenOnAStillVisibleNode() {
        var s = session(ownerId, true, "Java", "Spring");
        var tags = tagIds(ownerId);
        var shared = nodeOf(s.getId());
        bookmark(member(), shared, tags.get("java"), TargetType.NODE, "2026-09-30T01:00:00Z");
        bookmark(member(), shared, tags.get("spring"), TargetType.NODE, "2026-09-30T01:00:00Z");
        assertThat(names("today")).containsExactly("Java", "Spring");

        s.update(null, null, null, null, List.of("Java"));

        assertThat(names("today")).containsExactly("Java");
    }

    @Test
    void hashtagNeedsAPublicSessionOrAPublicInterest() {
        var s =
                sessions.findAll().stream()
                        .filter(b -> b.getUserId().equals(ownerId))
                        .findFirst()
                        .orElseThrow();
        bookmark(member(), "2026-09-30T01:00:00Z");
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
    @DisplayName("[COM-6-3 R2] 비공개로 돌린 태그는 목록에서 빠지고 같은 세션의 다른 태그는 남는다")
    void hiddenHashtagLeavesAndTheOtherTagsOfTheSessionStay() {
        var s = session(ownerId, true, "Java", "Spring");
        var tags = tagIds(ownerId);
        var shared = nodeOf(s.getId());
        bookmark(member(), shared, tags.get("java"), TargetType.NODE, "2026-09-30T01:00:00Z");
        bookmark(member(), shared, tags.get("spring"), TargetType.NODE, "2026-09-30T01:00:00Z");
        bookmark(member(), tags.get("spring"), "2026-09-30T01:00:00Z");
        var profile = profiles.findById(ownerId).orElseGet(() -> new CommunityProfile(ownerId));
        profile.replaceHiddenHashtags(List.of("SPRING"));
        profiles.saveAndFlush(profile);

        assertThat(names("today")).containsExactly("Java");
    }

    @Test
    void withdrawnOwnerTakesTheirHashtagsOut() {
        bookmark(member(), "2026-09-30T01:00:00Z");
        assertThat(names("today")).containsExactly("Java");

        members.findById(ownerId)
                .orElseThrow()
                .withdraw("test", LocalDateTime.of(2026, 9, 30, 12, 0));

        assertThat(names("today")).isEmpty();
    }

    @Test
    void readingDoesNotCreateNodes() {
        var s =
                sessions.findAll().stream()
                        .filter(b -> b.getUserId().equals(ownerId))
                        .findFirst()
                        .orElseThrow();
        bookmark(member(), "2026-09-30T01:00:00Z");
        s.replaceSources(
                List.of(
                        new Source("a", "https://example.com", "x"),
                        new Source("b", "https://example.org/new", "y")));
        long before = nodes.count();

        assertThat(names("today")).containsExactly("Java");

        assertThat(nodes.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("[COM-6-3 S3] 개수를 안 보내면 최대 10개까지 준다")
    void defaultLimitIsTen() throws Exception {
        session(ownerId, true, "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11");
        tagIds(ownerId).values().forEach(id -> bookmark(member(), id, "2026-09-30T01:00:00Z"));

        assertThat(tagIds(ownerId)).hasSizeGreaterThan(10);
        assertThat(names("today")).hasSize(10);
    }

    @Test
    @DisplayName("[COM-6-3 R3] today · week 밖의 기간은 400 \"기간은 today 또는 week 만 쓸 수 있습니다\"")
    void otherPeriodIsRejectedWithMessage() throws Exception {
        for (var period : List.of("month", "오늘")) {
            mvc.perform(
                            get(TRENDING)
                                    .param("period", period)
                                    .header("Authorization", auth(viewerId)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("기간은 today 또는 week 만 쓸 수 있습니다"));
        }
    }

    private record Row(int rank, long count, long trend) {}

    private Row row(String period, String name) {
        try {
            var body = trending(period);
            List<Map<String, Object>> found =
                    JsonPath.read(body, "$.data.hashtags[?(@.name == '" + name + "')]");
            assertThat(found).hasSize(1);
            var hashtag = found.get(0);
            return new Row(
                    (int) hashtag.get("rank"),
                    ((Number) hashtag.get("count")).longValue(),
                    ((Number) hashtag.get("trend")).longValue());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private List<String> names(String period) {
        try {
            return JsonPath.read(trending(period), "$.data.hashtags[*].name");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private String trending(String period) throws Exception {
        return mvc.perform(
                        get(TRENDING)
                                .param("period", period)
                                .header("Authorization", auth(viewerId)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private Map<String, Long> tagIds(Long owner) {
        var ids = new HashMap<String, Long>();
        graphs.graph(owner, null)
                .hashtags()
                .forEach(t -> ids.put(t.name().toLowerCase(Locale.ROOT), t.hashtagId()));
        return ids;
    }

    private Long nodeOf(Long sessionId) {
        return nodes.findByOwnerId(ownerId).stream()
                .filter(n -> n.getSessionId().equals(sessionId))
                .findFirst()
                .orElseThrow()
                .getId();
    }

    private Long member() {
        return members.saveAndFlush(
                        new Member(
                                SocialProvider.KAKAO,
                                UUID.randomUUID().toString(),
                                "T" + UUID.randomUUID().toString().substring(0, 7),
                                null))
                .getId();
    }

    /** 지금 시계로 태그 묶음을 북마크하고 북마크 id 를 돌려준다 */
    private Long saveTag(Long actor) {
        return bookmarks
                .save(actor, TargetType.HASHTAG, List.of(tag), SaveType.HASHTAG_BUNDLE, null, null)
                .bookmarkId();
    }

    private void bookmark(Long actor, String time) {
        bookmark(actor, tag, tag, TargetType.HASHTAG, time);
    }

    private void bookmark(Long actor, Long hashtag, String time) {
        bookmark(actor, hashtag, hashtag, TargetType.HASHTAG, time);
    }

    private void bookmark(Long actor, Long target, Long hashtag, TargetType type, String time) {
        event(actor, type, target, hashtag, ActivityKind.BOOKMARK, time);
    }

    private void event(
            Long actor,
            TargetType type,
            Long target,
            Long hashtag,
            ActivityKind kind,
            String time) {
        var instant = Instant.parse(time);
        activity.saveAndFlush(
                new CommunityActivity(
                        actor,
                        type,
                        target,
                        hashtag,
                        kind,
                        instant,
                        instant.atZone(KST).toLocalDate()));
    }
}
