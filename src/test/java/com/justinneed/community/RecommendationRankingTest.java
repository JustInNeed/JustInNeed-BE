package com.justinneed.community;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.community.bookmark.BookmarkCommandService;
import com.justinneed.community.bookmark.SaveType;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.catalog.CommunityHashtagRepository;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class RecommendationRankingTest extends CommunityTestSupport {
    private static final String INTERESTS = "/community/recommendations/mindmaps/interests";
    private static final String RECENT = "/community/recommendations/mindmaps/recent-bookmarks";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 10, 1, 12, 0);

    @Autowired BookmarkCommandService bookmarks;
    @Autowired CommunityHashtagRepository hashtags;

    private ResultActions recommend(String path, String... params) throws Exception {
        var request = get(path).header("Authorization", auth(viewerId));
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        return mvc.perform(request);
    }

    private BrowsingSession card(Long owner, int minutes, String... tags) {
        LocalDateTime at = BASE.plusMinutes(minutes);
        BrowsingSession session = new BrowsingSession(owner, "제목" + minutes, at.minusMinutes(5));
        session.complete(at, 1);
        session.update(null, null, true, false, List.of(tags));
        session.replaceSources(List.of(new Source("글 제목", "https://example.com/" + minutes, "발췌")));
        new Summary(session, "본문");
        BrowsingSession saved = sessions.saveAndFlush(session);
        cardSync.sync(saved);
        return saved;
    }

    private void interests(String... tags) {
        var viewer = new CommunityProfile(viewerId);
        viewer.replaceInterests(List.of(tags));
        profiles.saveAndFlush(viewer);
    }

    private static Integer[] ids(BrowsingSession... sessions) {
        return Arrays.stream(sessions).map(s -> s.getId().intValue()).toArray(Integer[]::new);
    }

    @Test
    @DisplayName("[COM-6-1 S2] 겹치는 태그 수 → 최근 활동 → id 큰 순으로 추천한다")
    void moreMatchingTagsBeatRecencyWhichBeatsId() throws Exception {
        interests("가", "나", "다");
        var oneOld = card(ownerId, 0, "가");
        var threeOldest = card(ownerId, -10, "가", "나", "다");
        var oneNewLow = card(ownerId, 5, "나");
        var oneNewHigh = card(ownerId, 5, "다");
        var twoOlder = card(ownerId, -5, "가", "나");

        recommend(INTERESTS, "limit", "5")
                .andExpect(
                        jsonPath("$.data.mindmaps[*].mindmapId")
                                .value(
                                        contains(
                                                ids(
                                                        threeOldest,
                                                        twoOlder,
                                                        oneNewHigh,
                                                        oneNewLow,
                                                        oneOld))));
    }

    @Test
    void limitKeepsTheBestRanked() throws Exception {
        interests("가", "나");
        card(ownerId, 9, "가");
        var best = card(ownerId, 0, "가", "나");
        var next = card(ownerId, 10, "가");

        recommend(INTERESTS, "limit", "2")
                .andExpect(
                        jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(best, next))));
    }

    @Test
    @DisplayName("[COM-6-1 R1] 공개 세션마다 카드 한 장이고 내 카드는 빠진다")
    void oneCardPerSessionNeverMyOwnWithAllPublicTags() throws Exception {
        interests("여행");
        var first = card(ownerId, 1, "여행", "로마", "숨김");
        var second = card(ownerId, 0, "여행");
        card(viewerId, 2, "여행");
        var profile = new CommunityProfile(ownerId);
        profile.changeVisibility(NodeVisibility.CONTENT);
        profile.replaceHiddenHashtags(List.of("숨김"));
        profiles.saveAndFlush(profile);

        recommend(INTERESTS, "limit", "10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basisHashtags").value(contains("여행")))
                .andExpect(
                        jsonPath("$.data.mindmaps[*].mindmapId")
                                .value(contains(ids(first, second))))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.userId").value(ownerId))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.nickname").value("Owner"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value("제목1"))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags").value(contains("여행", "로마")))
                .andExpect(
                        jsonPath("$.data.mindmaps[0].lastActivityAt").value("2026-10-01T12:01:00"))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls[0].title").value("글 제목"))
                .andExpect(
                        jsonPath("$.data.mindmaps[0].relatedUrls[0].url")
                                .value("https://example.com/1"));
    }

    @Test
    @DisplayName(
            "[COM-6-1 R2] [X-CMT-01 R1] 비공개 태그는 매칭에 쓰지 않고 URL만 공개 카드는 제목 · 요약 · 링크 제목이 null 이다")
    void urlOnlyCardsHideTitleAndHiddenTagsDoNotCountAsMatches() throws Exception {
        interests("가", "숨김");
        var two = card(ownerId, 0, "가", "숨김");
        var newer =
                card(
                        members.saveAndFlush(
                                        new Member(SocialProvider.KAKAO, "third", "Third", null))
                                .getId(),
                        1,
                        "가");
        var profile = new CommunityProfile(ownerId);
        profile.replaceHiddenHashtags(List.of("숨김"));
        profiles.saveAndFlush(profile);

        // 숨긴 태그를 세면 two 가 앞선다
        recommend(INTERESTS)
                .andExpect(
                        jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(newer, two))))
                .andExpect(jsonPath("$.data.mindmaps[1].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[1].snippet").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[1].relatedUrls[0].title").value(nullValue()));
    }

    @Test
    void cardWithoutUrlsOrNodesIsStillACard() throws Exception {
        interests("여행");
        LocalDateTime at = BASE;
        BrowsingSession session = new BrowsingSession(ownerId, "빈 카드", at);
        session.complete(at, 1);
        session.update(null, null, true, false, List.of("여행"));
        BrowsingSession saved = sessions.saveAndFlush(session);
        cardSync.sync(saved);

        recommend(INTERESTS)
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(saved))))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(0));
    }

    @Test
    void matchIsAWholeTagIgnoringCaseNotASubstring() throws Exception {
        interests("java");
        card(ownerId, 0, "JavaScript");
        var exact = card(ownerId, 1, "JAVA");

        recommend(INTERESTS)
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(exact))));
    }

    @Test
    void withdrawnOwnersAreLeftOut() throws Exception {
        interests("여행");
        card(ownerId, 0, "여행");
        var gone = members.findById(ownerId).orElseThrow();
        gone.withdraw("이유", LocalDateTime.now());
        members.saveAndFlush(gone);

        recommend(INTERESTS).andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
    }

    @Test
    @DisplayName("[COM-6-1 S1] 추천 기준 태그는 10개까지이고 그 10개로만 찾는다")
    void basisStopsAtTenAndOnlyThoseTenMatch() throws Exception {
        List<String> tags = IntStream.range(0, 12).mapToObj(i -> "태그" + i).toList();
        interests(tags.toArray(String[]::new));
        var tenth = card(ownerId, 0, "태그9");
        card(ownerId, 1, "태그10");

        recommend(INTERESTS)
                .andExpect(jsonPath("$.data.basisHashtags", hasSize(10)))
                .andExpect(jsonPath("$.data.basisHashtags[9]").value("태그9"))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(tenth))));
    }

    @Test
    void recentBookmarksDoNotExcludeTheCardIJustSaved() throws Exception {
        var saved = card(ownerId, 0, "여행");
        var tag = hashtags.findByOwnerIdOrderByIdAsc(ownerId).get(0).getId();
        bookmarks.save(
                viewerId, TargetType.HASHTAG, List.of(tag), SaveType.HASHTAG_BUNDLE, null, null);

        recommend(RECENT)
                .andExpect(jsonPath("$.data.basisHashtags").value(contains("여행")))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(saved))));
    }

    @Test
    @DisplayName("[COM-6-1 S3] 최근 저장 기준 태그는 최근에 저장한 북마크의 태그부터 온다")
    void recentBasisFollowsSaveOrderNewestFirst() throws Exception {
        card(ownerId, 0, "가", "나", "다");
        var tags = hashtags.findByOwnerIdOrderByIdAsc(ownerId);
        for (var tag : tags) {
            bookmarks.save(
                    viewerId,
                    TargetType.HASHTAG,
                    List.of(tag.getId()),
                    SaveType.HASHTAG_BUNDLE,
                    null,
                    null);
        }
        var names = tags.stream().map(t -> t.getName()).toList();

        recommend(RECENT)
                .andExpect(
                        jsonPath("$.data.basisHashtags")
                                .value(contains(names.get(2), names.get(1), names.get(0))));
    }

    @Test
    @DisplayName("[COM-6-1 R3] 조회 개수가 1~100 밖이면 400 \"조회 개수는 1개부터 100개까지 입력할 수 있습니다.\"")
    void limitOutOfRangeIsRejectedWithMessage() throws Exception {
        for (String path : List.of(INTERESTS, RECENT)) {
            for (String limit : List.of("0", "101")) {
                recommend(path, "limit", limit)
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message").value("조회 개수는 1개부터 100개까지 입력할 수 있습니다."));
            }
        }
    }
}
