package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.Source;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 커뮤니티 둘러보기 (/community): 해시태그 검색 드롭다운 · 떠오르는 해시태그 · 추천 마인드맵 피드.
 * FE: src/features/community/CommunityView.tsx · FeedCard.tsx, 목 COMMUNITY_FEED · TRENDING_HASHTAGS(_WEEK) · COMMUNITY_RELATED_URLS.
 * 시드: wanderer(프라하 · 비엔나 · 야간열차, 비공개 카페) + foodtrip(파리 미식 지도).
 */
@DisplayName("커뮤니티 둘러보기 화면")
class CommunityFeedScenarioTest extends ScenarioTestSupport {

    private static final String TRENDING = "/community/recommendations/hashtags/trending";
    private static final String BY_INTERESTS = "/community/recommendations/mindmaps/interests";
    private static final String BY_RECENT_BOOKMARKS = "/community/recommendations/mindmaps/recent-bookmarks";
    private static final String INVALID_HASHTAG = "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다.";

    private Member me;
    private Wanderer wanderer;
    private Member foodtrip;

    @BeforeEach
    void seed() {
        me = member("sprout");
        wanderer = seedWanderer();
        foodtrip = member("foodtrip");
        session(foodtrip, "파리 미식 지도 — 1구부터 20구까지", "2026-04-26T12:00:00", "2026-04-26T13:30:00", 20, false, true,
                List.of("파리", "먹거리", "미슐랭"),
                List.of(new Source("미슐랭 빕구르망 파리", "https://guide.michelin.com/en/ile-de-france/paris/restaurants/bib-gourmand",
                                "가성비 좋은 비스트로 목록."),
                        new Source("파리 관광청", "https://www.parisinfo.com/", "마레 지구 산책 코스.")),
                "# 파리 미식");
    }

    private void addInterest(Member member, String hashtag) throws Exception {
        mvc.perform(post("/community/me/interests").with(as(member)).with(json(Map.of("hashtag", hashtag))))
                .andExpect(status().isCreated());
    }

    private void exploreHashtag(Member actor, Member owner, String hashtag) throws Exception {
        mvc.perform(patch("/community/exploring").with(as(actor)).with(json(Map.of(
                        "targetType", "HASHTAG", "targetId", hashtagId(owner, hashtag), "exploring", true))))
                .andExpect(okEnvelope());
    }

    private void bookmarkHashtag(Member actor, Member owner, String hashtag) throws Exception {
        mvc.perform(post("/community/bookmarks").with(as(actor)).with(json(Map.of(
                        "targetType", "HASHTAG", "targetIds", List.of(hashtagId(owner, hashtag)),
                        "saveType", "HASHTAG_BUNDLE"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("검색 드롭다운 '프로필 추천'(COM-5-1): 그 해시태그를 공개로 가진 사람이 닉네임 · 노드 수와 함께 온다")
    void searchProfiles() throws Exception {
        mvc.perform(get("/community/search/profiles").param("hashtag", "프라하").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.profiles", hasSize(1)))
                .andExpect(jsonPath("$.data.profiles[0].userId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.profiles[0].nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.profiles[0].matchedHashtags").value(contains("프라하")))
                .andExpect(jsonPath("$.data.profiles[0].totalNodeCount").value(6))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("검색 드롭다운 '마인드맵 탐색'(COM-5-2): 그 해시태그에 이어진 노드가 있는 마인드맵만 온다 (관심 태그만 있으면 프로필엔 나와도 마인드맵엔 안 나온다)")
    void searchMindmaps() throws Exception {
        mvc.perform(get("/community/search/mindmaps").param("hashtag", "파리").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps", hasSize(1)))
                .andExpect(jsonPath("$.data.mindmaps[0].mindmapId").value(foodtrip.getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].ownerId").value(foodtrip.getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].nickname").value("foodtrip"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(notNullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags").value(contains("파리")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(2));

        // 카페투어는 wanderer 의 관심 해시태그일 뿐 노드가 없다
        mvc.perform(get("/community/search/profiles").param("hashtag", "카페투어").with(as(me)))
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(wanderer.id().intValue())));
        mvc.perform(get("/community/search/mindmaps").param("hashtag", "카페투어").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
    }

    @Test
    @DisplayName("비공개 해시태그(카페)로 찾으면 프로필 · 마인드맵 모두 아무도 안 나온다")
    void searchHidesPrivateHashtags() throws Exception {
        mvc.perform(get("/community/search/profiles").param("hashtag", "카페").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.profiles", hasSize(0)));
        mvc.perform(get("/community/search/mindmaps").param("hashtag", "카페").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
    }

    @Test
    @DisplayName("검색어에 '#' 을 붙이거나 비우면 400 형식 안내 — FE 가 '#' 을 떼고 보내야 한다")
    void searchKeywordValidation() throws Exception {
        mvc.perform(get("/community/search/profiles").param("hashtag", "#프라하").with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_HASHTAG));
        mvc.perform(get("/community/search/mindmaps").param("hashtag", "").with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_HASHTAG));
    }

    @Test
    @DisplayName("오늘 / 이번 주 떠오르는 해시태그(COM-6-3): 탐색중 · 북마크한 사람 수로 순위가 매겨진다")
    void trendingHashtags() throws Exception {
        exploreHashtag(me, wanderer.member(), "프라하");
        exploreHashtag(foodtrip, wanderer.member(), "프라하");
        bookmarkHashtag(me, wanderer.member(), "야간열차");

        for (String period : List.of("TODAY", "WEEK")) {
            mvc.perform(get(TRENDING).param("period", period).with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.period").value(period))
                    .andExpect(jsonPath("$.data.hashtags[*].name").value(contains("프라하", "야간열차")))
                    .andExpect(jsonPath("$.data.hashtags[*].rank").value(contains(1, 2)))
                    .andExpect(jsonPath("$.data.hashtags[0].count").value(2))
                    .andExpect(jsonPath("$.data.hashtags[0].trend").value(2))
                    .andExpect(jsonPath("$.data.hashtags[1].count").value(1))
                    .andExpect(jsonPath("$.data.hashtags[1].trend").value(1));
        }
    }

    @Test
    @DisplayName("기간을 안 보내면 오늘(TODAY) 기준이고, 활동이 없으면 빈 목록이다")
    void trendingDefaultsToToday() throws Exception {
        mvc.perform(get(TRENDING).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.period").value("TODAY"))
                .andExpect(jsonPath("$.data.hashtags", hasSize(0)));
    }

    @Test
    @DisplayName("관심 해시태그 기반 추천(COM-6-1): 내 관심 태그와 겹치는 다른 사람 마인드맵이 겹친 태그 · 연관 URL 과 함께 온다 (내 것은 빠진다)")
    void recommendByInterests() throws Exception {
        addInterest(me, "프라하");
        addInterest(me, "파리");
        session(me, "파리 미술관 4곳 비교", "2026-04-24T10:05:00", "2026-04-24T11:30:00", 14, true, true,
                List.of("파리"), List.of(new Source("루브르", "https://www.louvre.fr/en", "야간 개장")), null);

        mvc.perform(get(BY_INTERESTS).param("limit", "3").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.basisHashtags").value(contains("프라하", "파리")))
                .andExpect(jsonPath("$.data.mindmaps[*].ownerId").value(contains(ids(wanderer.id(), foodtrip.getId()))))
                .andExpect(jsonPath("$.data.mindmaps[0].mindmapId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(notNullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].matchedHashtags").value(contains("프라하")))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls").value(contains(
                        "https://www.nightjet.com/en", "https://www.cd.cz/en/",
                        "https://www.hrad.cz/en/prague-castle-for-visitors", "https://www.prague.eu/en")))
                .andExpect(jsonPath("$.data.mindmaps[1].matchedHashtags").value(contains("파리")));

        mvc.perform(get(BY_INTERESTS).param("limit", "1").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(1)));
    }

    @Test
    @DisplayName("관심 해시태그가 없으면 추천 기준 · 마인드맵이 모두 빈 목록이다")
    void noInterestsNoRecommendations() throws Exception {
        mvc.perform(get(BY_INTERESTS).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.basisHashtags", hasSize(0)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
    }

    @Test
    @DisplayName("최근 저장한 해시태그 기반 추천(COM-6-2): 방금 북마크한 해시태그가 추천 기준이 된다")
    void recommendByRecentBookmarks() throws Exception {
        mvc.perform(get(BY_RECENT_BOOKMARKS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));

        bookmarkHashtag(me, foodtrip, "미슐랭");

        mvc.perform(get(BY_RECENT_BOOKMARKS).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.basisHashtags").value(contains("미슐랭")))
                .andExpect(jsonPath("$.data.mindmaps[*].ownerId").value(contains(ids(foodtrip.getId()))))
                .andExpect(jsonPath("$.data.mindmaps[0].matchedHashtags").value(contains("미슐랭")))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls").value(hasItems(
                        "https://guide.michelin.com/en/ile-de-france/paris/restaurants/bib-gourmand",
                        "https://www.parisinfo.com/")));
    }

    @Test
    @DisplayName("추천 개수(limit)는 1~100, 기간은 today · week(대소문자 무시)만 받고 그 밖이면 400")
    void recommendationParameterValidation() throws Exception {
        mvc.perform(get(BY_INTERESTS).param("limit", "0").with(as(me))).andExpect(status().isBadRequest());
        mvc.perform(get(BY_RECENT_BOOKMARKS).param("limit", "101").with(as(me))).andExpect(status().isBadRequest());
        mvc.perform(get(TRENDING).param("period", "MONTH").with(as(me))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("로그인하지 않으면 검색 · 추천 · 떠오르는 해시태그 · 탐색중 조회 모두 401")
    void requiresLogin() throws Exception {
        for (String path : List.of("/community/search/profiles?hashtag=프라하", "/community/search/mindmaps?hashtag=프라하",
                BY_INTERESTS, BY_RECENT_BOOKMARKS, TRENDING, "/community/exploring?targetType=HASHTAG&targetId=1")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    @DisplayName("FE 탭 값 그대로 period=today / week 로 보내도 떠오르는 해시태그가 온다")
    void trendingAcceptsLowercasePeriod() throws Exception {
        mvc.perform(get(TRENDING).param("period", "today").with(as(me))).andExpect(okEnvelope());
        mvc.perform(get(TRENDING).param("period", "week").with(as(me))).andExpect(okEnvelope());
    }

    @Test
    @DisplayName("떠오르는 해시태그마다 합계(count)와 이전 기간 대비 증감(trend)이 온다")
    void trendingHasCountAndTrend() throws Exception {
        exploreHashtag(me, wanderer.member(), "프라하");

        mvc.perform(get(TRENDING).param("period", "TODAY").with(as(me)))
                .andExpect(jsonPath("$.data.hashtags[0].count").value(1))
                .andExpect(jsonPath("$.data.hashtags[0].trend").value(1));
    }

    @Test
    @Disabled("API 미구현: 추천 마인드맵의 연관 URL 이 문자열뿐 — FE RelatedUrl{title, url} 로 보여줌 "
            + "(relatedUrls[].title · url 은 제안, URL_ONLY 면 title 에 URL 을 넣는 방식 등 결정 필요), 구현되면 켠다")
    @DisplayName("추천 마인드맵과 함께 오는 연관 URL 에 제목과 주소가 같이 온다")
    void relatedUrlsHaveTitles() throws Exception {
        addInterest(me, "파리");

        mvc.perform(get(BY_INTERESTS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls[0].url").value("https://guide.michelin.com/en/ile-de-france/paris/restaurants/bib-gourmand"))
                .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls[*].title", everyItem(notNullValue())));
    }

    @Test
    @Disabled("API 미구현: 피드 카드(FE CommunityPost)가 읽는 작성자 닉네임 · 노드 수 · 좋아요 · 북마크 · 탐색중 수가 추천 응답에 없고 "
            + "title 은 고정 영문 'Community mindmap' — 필드명 nickname · nodeCount · likeCount · bookmarkCount · exploringCount 는 제안, 구현되면 켠다")
    @DisplayName("추천 마인드맵 카드에 작성자 닉네임 · 제목 · 노드 수 · 좋아요 · 북마크 · 탐색중 수가 온다")
    void recommendationCardFields() throws Exception {
        addInterest(me, "프라하");

        mvc.perform(get(BY_INTERESTS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[0].nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(not("Community mindmap")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(6))
                .andExpect(jsonPath("$.data.mindmaps[0]", hasKey("likeCount")))
                .andExpect(jsonPath("$.data.mindmaps[0]", hasKey("bookmarkCount")))
                .andExpect(jsonPath("$.data.mindmaps[0]", hasKey("exploringCount")));
    }

    @Test
    @Disabled("API 미구현: 피드 마인드맵 카드 좋아요 (C표 '좋아요 대상: 프로필 + 피드 마인드맵 카드' 결정) — "
            + "PATCH /community/mindmaps/{mindmapId}/like {liked} 는 제안(프로필 좋아요와 같은 모양), 구현되면 켠다")
    @DisplayName("피드 카드에서 마인드맵 좋아요를 누르면 수가 오르고 다시 누르면 내려간다")
    void mindmapLike() throws Exception {
        mvc.perform(patch("/community/mindmaps/{id}/like", wanderer.id()).with(as(me)).with(json(Map.of("liked", true))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.liked").value(true))
                .andExpect(jsonPath("$.data.likeCount").value(1));
        mvc.perform(patch("/community/mindmaps/{id}/like", wanderer.id()).with(as(me)).with(json(Map.of("liked", false))))
                .andExpect(jsonPath("$.data.likeCount").value(0));
    }

    @Test
    @Disabled("API 미구현: 관심사가 비슷한 사람 (A표 반영 결정) — GET /community/recommendations/profiles/similar 와 "
            + "응답 profiles[].userId · nickname · matchedHashtags 는 제안, 구현되면 켠다")
    @DisplayName("관심사가 비슷한 사람: 내 관심 해시태그와 겹치는 사람이 겹친 태그와 함께 온다 (나는 빠진다)")
    void similarPeople() throws Exception {
        addInterest(me, "파리");
        addInterest(foodtrip, "파리");

        mvc.perform(get("/community/recommendations/profiles/similar").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(ids(foodtrip.getId()))))
                .andExpect(jsonPath("$.data.profiles[0].nickname").value("foodtrip"))
                .andExpect(jsonPath("$.data.profiles[0].matchedHashtags").value(contains("파리")));
    }

    @Test
    @Disabled("FE 기대와 다름: 필수 쿼리 파라미터(hashtag, targetId 등)를 빠뜨리면 MissingServletRequestParameterException 이 "
            + "500 '서버 오류가 발생했습니다.' 로 나감 — 400 으로 바꾼 뒤 켠다")
    @DisplayName("검색어 · 대상 id 같은 필수 파라미터를 빠뜨리면 400")
    void missingRequiredParameterIs400() throws Exception {
        mvc.perform(get("/community/search/profiles").with(as(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/community/exploring").param("targetType", "HASHTAG").with(as(me)))
                .andExpect(status().isBadRequest());
    }

    /**
     * wanderer 는 카페 · 슬로우트래블 을 비공개로 두었다 (카페 세션 · 슬로우트래블 비공개 세션).
     * 카페는 잠깐 공개로 돌려 내가 북마크 · 탐색한 뒤 다시 비공개로 돌리는 식으로, 비공개가 아니었다면 추천 · 집계에 걸렸을 상황을 만든다.
     */
    @Nested
    @DisplayName("다른 사람이 비공개로 둔 해시태그는 추천 피드 카드 · 추천 기준 · 떠오르는 해시태그에 나오지 않는다")
    class PrivateHashtagsStayOutOfFeed {

        private static final String CAFE_URL = "https://www.cafecentral.wien/en/";

        private void setWandererHashtagPublic(String hashtag, boolean isPublic) throws Exception {
            mvc.perform(patch("/community/me/hashtags/{id}/visibility", hashtagId(wanderer.member(), hashtag))
                            .with(as(wanderer.member())).with(json(Map.of("isPublic", isPublic))))
                    .andExpect(okEnvelope());
        }

        private void noCardMentionsPrivateHashtags(String path) throws Exception {
            mvc.perform(get(path).with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.mindmaps[*].matchedHashtags[*]").value(not(hasItem("카페"))))
                    .andExpect(jsonPath("$.data.mindmaps[*].matchedHashtags[*]").value(not(hasItem("슬로우트래블"))))
                    .andExpect(jsonPath("$.data.mindmaps[*].relatedUrls[*]").value(not(hasItem(CAFE_URL))));
        }

        @Test
        @DisplayName("관심 해시태그 기반(COM-6-1): 내 관심이 남의 비공개 태그와만 겹치면 그 마인드맵은 추천되지 않고, 공개 태그로 추천돼도 카드에 비공개 태그 · 그 노드 URL 이 없다")
        void interestRecommendationIgnoresPrivateHashtags() throws Exception {
            addInterest(me, "카페");
            addInterest(me, "슬로우트래블");

            mvc.perform(get(BY_INTERESTS).with(as(me)))
                    .andExpect(okEnvelope())
                    // 추천 기준은 내가 직접 등록한 관심 해시태그 그대로다 (남의 비공개 태그가 새는 것이 아님)
                    .andExpect(jsonPath("$.data.basisHashtags").value(contains("카페", "슬로우트래블")))
                    .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));

            addInterest(me, "비엔나");

            mvc.perform(get(BY_INTERESTS).with(as(me)))
                    .andExpect(jsonPath("$.data.mindmaps[*].ownerId").value(contains(ids(wanderer.id()))))
                    .andExpect(jsonPath("$.data.mindmaps[0].matchedHashtags").value(contains("비엔나")))
                    .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls").value(containsInAnyOrder(
                            "https://www.wien.info/en", "https://www.belvedere.at/en")));
            noCardMentionsPrivateHashtags(BY_INTERESTS);
        }

        @Test
        @DisplayName("최근 저장 기반(COM-6-2): 저장한 뒤 작성자가 비공개로 돌린 해시태그로는 마인드맵이 추천되지 않고, 카드에도 그 태그 · 노드 URL 이 없다")
        void recentBookmarkRecommendationIgnoresPrivateHashtags() throws Exception {
            setWandererHashtagPublic("카페", true);
            bookmarkHashtag(me, wanderer.member(), "카페");
            // 공개였을 땐 카페로 wanderer 가 추천된다 (비공개가 아니면 걸리는 상황인지 확인)
            mvc.perform(get(BY_RECENT_BOOKMARKS).with(as(me)))
                    .andExpect(jsonPath("$.data.mindmaps[0].matchedHashtags").value(contains("카페")));

            setWandererHashtagPublic("카페", false);

            mvc.perform(get(BY_RECENT_BOOKMARKS).with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));

            bookmarkHashtag(me, wanderer.member(), "비엔나");

            mvc.perform(get(BY_RECENT_BOOKMARKS).with(as(me)))
                    .andExpect(jsonPath("$.data.mindmaps[*].ownerId").value(contains(ids(wanderer.id()))))
                    .andExpect(jsonPath("$.data.mindmaps[0].matchedHashtags").value(contains("비엔나")))
                    .andExpect(jsonPath("$.data.mindmaps[0].relatedUrls").value(containsInAnyOrder(
                            "https://www.wien.info/en", "https://www.belvedere.at/en")));
            noCardMentionsPrivateHashtags(BY_RECENT_BOOKMARKS);
        }

        @Test
        @DisplayName("최근 저장 기반(COM-6-2): 작성자가 나중에 비공개로 돌린 해시태그는 추천 기준(basisHashtags)에도 안 나오고, 다시 공개로 돌리면 돌아온다")
        void recentBookmarkBasisHidesPrivateHashtags() throws Exception {
            setWandererHashtagPublic("카페", true);
            bookmarkHashtag(me, wanderer.member(), "카페");
            bookmarkHashtag(me, wanderer.member(), "비엔나");
            setWandererHashtagPublic("카페", false);

            mvc.perform(get(BY_RECENT_BOOKMARKS).with(as(me)))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.basisHashtags").value(contains("비엔나")));

            // 북마크 스냅샷은 그대로라 작성자가 다시 공개로 돌리면 추천 기준에 다시 나온다
            setWandererHashtagPublic("카페", true);

            mvc.perform(get(BY_RECENT_BOOKMARKS).with(as(me)))
                    .andExpect(jsonPath("$.data.basisHashtags").value(contains("비엔나", "카페")));
        }

        @Test
        @DisplayName("떠오르는 해시태그(COM-6-3): 비공개로 돌린 해시태그 · 그 세션 노드에 쌓인 탐색 · 북마크 활동은 오늘 · 이번 주 순위에 안 나온다")
        void trendingIgnoresPrivateHashtags() throws Exception {
            setWandererHashtagPublic("카페", true);
            exploreHashtag(me, wanderer.member(), "카페");
            bookmarkHashtag(me, wanderer.member(), "카페");
            Long cafeNode = nodeId(foodtrip, wanderer.member(), CAFE_URL); // 카페 세션 노드: 비엔나 · 카페 태그에 이어짐
            mvc.perform(patch("/community/exploring").with(as(foodtrip))
                            .with(json(Map.of("targetType", "NODE", "targetId", cafeNode, "exploring", true))))
                    .andExpect(okEnvelope());
            exploreHashtag(me, wanderer.member(), "프라하");
            // 공개였을 땐 카페가 순위에 오른다 (비공개가 아니면 걸리는 상황인지 확인)
            mvc.perform(get(TRENDING).param("period", "TODAY").with(as(me)))
                    .andExpect(jsonPath("$.data.hashtags[*].name").value(hasItem("카페")));

            setWandererHashtagPublic("카페", false);

            for (String period : List.of("TODAY", "WEEK")) {
                mvc.perform(get(TRENDING).param("period", period).with(as(me)))
                        .andExpect(okEnvelope())
                        // 카페만 빠진다. 같은 노드 활동으로 붙은 비엔나는 공개 태그라 남는다
                        .andExpect(jsonPath("$.data.hashtags[*].name").value(contains("비엔나", "프라하")))
                        .andExpect(jsonPath("$.data.hashtags[0].count").value(1));
            }
        }
    }
}
