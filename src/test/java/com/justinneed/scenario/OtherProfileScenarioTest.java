package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.BrowsingSession;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 다른 사용자 프로필 (/profile/{id}). FE: ProfileView.tsx (isMine=false), 목 otherProfile("wanderlust").
 * 시드 wanderer: 공개 세션 프라하 · 비엔나 · 야간열차, 비공개 해시태그 카페 · 슬로우트래블 (ScenarioTestSupport#seedWanderer).
 */
@DisplayName("다른 사용자 프로필 화면")
class OtherProfileScenarioTest extends ScenarioTestSupport {

    private static final String SESSION_NOT_FOUND = "세션을 찾을 수 없습니다.";
    private static final String PRAGUE_CASTLE = "https://www.hrad.cz/en/prague-castle-for-visitors";
    private static final String CAFE_URL = "https://www.cafecentral.wien/en/";

    private Member me;
    private Member foodtrip;
    private Wanderer wanderer;

    @BeforeEach
    void seed() {
        me = member("sprout");
        foodtrip = member("foodtrip");
        wanderer = seedWanderer();
    }

    private ResultActions bookmark(Member actor, String targetType, List<Long> targetIds, String saveType,
            Long sourceMindmapId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("targetType", targetType);
        body.put("targetIds", targetIds);
        body.put("saveType", saveType);
        body.put("sourceMindmapId", sourceMindmapId);
        return mvc.perform(post("/community/bookmarks").with(as(actor)).with(json(body)));
    }

    private ResultActions explore(Member actor, String targetType, Long targetId, boolean exploring) throws Exception {
        return mvc.perform(patch("/community/exploring").with(as(actor))
                .with(json(Map.of("targetType", targetType, "targetId", targetId, "exploring", exploring))));
    }

    private void showContent() throws Exception {
        mvc.perform(patch("/community/me/visibility").with(as(wanderer.member())).with(json(Map.of("visibility", "CONTENT"))))
                .andExpect(okEnvelope());
    }

    @Test
    @DisplayName("프로필을 열면 비공개 해시태그(카페 · 슬로우트래블)를 뺀 상위 5개 · 대표 해시태그와, 태그가 섞인 세션을 포함한 노드 수가 온다 (COM-1-2, COM-3-2~3-4)")
    void profileHidesPrivateHashtags() throws Exception {
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.userId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.totalNodeCount").value(7))
                .andExpect(jsonPath("$.data.topHashtags[*].name").value(contains("프라하", "비엔나", "야간열차", "체코", "카를교")))
                .andExpect(jsonPath("$.data.topHashtags[0].nodeCount").value(4))
                .andExpect(jsonPath("$.data.topHashtags[1].nodeCount").value(3))
                .andExpect(jsonPath("$.data.interests").value(contains("프라하", "비엔나", "야간열차", "카페투어")))
                .andExpect(jsonPath("$.data.sharedSessionCount").value(4))
                .andExpect(jsonPath("$.data.pinnedSessions[*].id").value(contains(
                        ids(wanderer.prague(), wanderer.cafe(), wanderer.nightjet()))))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.likedByMe").value(false));
    }

    @Test
    @DisplayName("마인드맵 탭: 비공개 해시태그만 없고, 해시태그 → 노드 연결선과 원문 URL 이 온다")
    void mindmapHidesPrivateHashtags() throws Exception {
        mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmapId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.ownerId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(containsInAnyOrder(
                        "야간열차", "프라하", "비엔나", "카를교", "체코", "카페투어")))
                .andExpect(jsonPath("$.data.nodes", hasSize(7)))
                .andExpect(jsonPath("$.data.nodes[*].sourceUrl").value(hasItems(CAFE_URL, PRAGUE_CASTLE)))
                .andExpect(jsonPath("$.data.edges", hasSize(13)))
                .andExpect(jsonPath("$.data.edges[*].from", everyItem(startsWith("tag:"))))
                .andExpect(jsonPath("$.data.edges[*].to", everyItem(startsWith("node:"))))
                .andExpect(jsonPath("$.data.topHashtags[*].name").value(not(hasItem("카페"))));
    }

    @Test
    @DisplayName("해시태그로 마인드맵을 거르면 그 태그의 노드만 오고, 비공개 해시태그 id 로 거르면 404")
    void mindmapFilterByHashtag() throws Exception {
        Long vienna = hashtagId(wanderer.member(), "비엔나");
        Long cafe = hashtagId(wanderer.member(), "카페");

        mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).param("hashtagId", vienna.toString()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes[*].sourceUrl").value(containsInAnyOrder(
                        "https://www.wien.info/en", "https://www.belvedere.at/en", CAFE_URL)));
        mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).param("hashtagId", cafe.toString()).with(as(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("리프 노드 → 원문 URL(COM-3-1): /nodes 는 공개 해시태그별 세션과 원문 URL 만 준다")
    void nodesByHashtag() throws Exception {
        mvc.perform(get("/community/profiles/{id}/nodes", wanderer.id()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[*].hashtag").value(contains("야간열차", "프라하", "비엔나", "카를교", "체코")))
                .andExpect(jsonPath("$.data[?(@.hashtag == '체코')].sessions[0].urls[0]").value(contains(PRAGUE_CASTLE)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '비엔나')].sessions[*].urls[*]").value(hasItem(CAFE_URL)))
                .andExpect(jsonPath("$.data[*].sessions[*].tags[*]").value(not(hasItem("카페"))));
    }

    @Test
    @DisplayName("노드 공개 범위가 URL_ONLY(기본)면 원문 URL 만 보인다 (CONTENT 는 CommunityGraphSourceScenarioTest)")
    void nodeContentFollowsVisibilityScope() throws Exception {
        String castle = "$.data.nodes[?(@.sourceUrl == '" + PRAGUE_CASTLE + "')]";
        mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).with(as(me)))
                .andExpect(jsonPath(castle + ".label").value(contains(PRAGUE_CASTLE)))
                .andExpect(jsonPath(castle + ".content").value(contains((Object) null)))
                .andExpect(jsonPath(castle + ".visibilityScope").value(contains("URL_ONLY")));
    }

    @Test
    @DisplayName("대표 해시태그: 다른 사람에겐 비공개(카페)가 빠지고, 주인에게는 다 보인다")
    void interestsHideHiddenForVisitors() throws Exception {
        mvc.perform(get("/community/profiles/{id}/interests", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data").value(contains("프라하", "비엔나", "야간열차", "카페투어")));
        mvc.perform(get("/community/profiles/{id}/interests", wanderer.id()).with(as(wanderer.member())))
                .andExpect(jsonPath("$.data").value(contains("프라하", "비엔나", "야간열차", "카페투어", "카페")));
    }

    @Test
    @DisplayName("세션 탭(COM-1-6): 공개 세션 전체가 고정 순서 먼저, 나머지는 뒤에 오고, URL_ONLY 면 제목 · 본문은 빠진다")
    void sharedSessionsTab() throws Exception {
        mvc.perform(get("/community/profiles/{id}/shared-sessions", wanderer.id()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(
                        ids(wanderer.prague(), wanderer.cafe(), wanderer.nightjet(), wanderer.vienna()))))
                .andExpect(jsonPath("$.data.sessions[*].isPinned").value(contains(true, true, true, false)))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.sessions[0].tags").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.sessions[0].urls").value(contains(PRAGUE_CASTLE, "https://www.prague.eu/en")))
                .andExpect(jsonPath("$.data.sessions[0].title").doesNotExist())
                .andExpect(jsonPath("$.data.sessions[0].markdown").doesNotExist())
                .andExpect(jsonPath("$.data.sessions[0].sources").doesNotExist());

        // 주인은 자기 공유 세션을 태그 · 내용까지 전부 본다
        mvc.perform(get("/community/profiles/{id}/shared-sessions", wanderer.id()).with(as(wanderer.member())))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(
                        ids(wanderer.prague(), wanderer.cafe(), wanderer.nightjet(), wanderer.vienna()))))
                .andExpect(jsonPath("$.data.sessions[0].title").value("프라하 1박 일정 짜기"))
                .andExpect(jsonPath("$.data.sessions[1].tags").value(contains("비엔나", "카페")));
    }

    @Test
    @DisplayName("공유 세션을 누르면(COM-1-6) 작성자 · 해시태그 · 원문 URL 이 오고, 주인이 CONTENT 로 열어 두면 제목 · 줄글까지 온다")
    void sharedSessionDetail() throws Exception {
        Long prague = wanderer.prague().getId();

        mvc.perform(get("/community/sessions/{id}", prague).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessionId").value(prague))
                .andExpect(jsonPath("$.data.ownerId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.hashtags").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.urls").value(contains(PRAGUE_CASTLE, "https://www.prague.eu/en")))
                .andExpect(jsonPath("$.data.title").value(nullValue()))
                .andExpect(jsonPath("$.data.summary").value(nullValue()))
                .andExpect(jsonPath("$.data.visibilityScope").value("URL_ONLY"));
        mvc.perform(get("/community/profiles/{userId}/shared-sessions/{sessionId}", wanderer.id(), prague).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.id").value(prague))
                .andExpect(jsonPath("$.data.urls", hasSize(2)));

        showContent();

        mvc.perform(get("/community/sessions/{id}", prague).with(as(me)))
                .andExpect(jsonPath("$.data.title").value("프라하 1박 일정 짜기"))
                .andExpect(jsonPath("$.data.summary").value("# 프라하 1박\n\n카를교 → 프라하 성 순서로 걷는다."))
                .andExpect(jsonPath("$.data.visibilityScope").value("CONTENT"));
    }

    @Test
    @DisplayName("공유 세션 상세: 고정 안 한 공개 세션도 열리고, 비공개 세션은 404")
    void sharedSessionDetailFollowsPublic() throws Exception {
        Long vienna = wanderer.vienna().getId();
        mvc.perform(get("/community/sessions/{id}", vienna).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessionId").value(vienna));
        mvc.perform(get("/community/profiles/{userId}/shared-sessions/{sessionId}", wanderer.id(), vienna).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isPinned").value(false));

        BrowsingSession blocked = wanderer.slow();
        mvc.perform(get("/community/sessions/{id}", blocked.getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        mvc.perform(get("/community/profiles/{userId}/shared-sessions/{sessionId}", wanderer.id(), blocked.getId())
                        .with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("없는 사용자 프로필을 열면 404 '회원을 찾을 수 없습니다.'")
    void unknownProfileIs404() throws Exception {
        mvc.perform(get("/community/profiles/{id}", 999_999).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "회원을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("프로필 좋아요(COM-4-1): 누르면 수가 오르고 likedByMe=true, 다시 누르면(해제) 내려간다. 같은 요청을 반복해도 한 번만 센다")
    void profileLikeToggle() throws Exception {
        String like = "/community/profiles/{id}/like";

        for (int i = 0; i < 2; i++) {
            mvc.perform(patch(like, wanderer.id()).with(as(me)).with(json(Map.of("liked", true))))
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.userId").value(wanderer.id()))
                    .andExpect(jsonPath("$.data.liked").value(true))
                    .andExpect(jsonPath("$.data.likeCount").value(1));
        }
        mvc.perform(patch(like, wanderer.id()).with(as(foodtrip)).with(json(Map.of("liked", true))))
                .andExpect(jsonPath("$.data.likeCount").value(2));
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.likeCount").value(2))
                .andExpect(jsonPath("$.data.likedByMe").value(true));

        mvc.perform(patch(like, wanderer.id()).with(as(me)).with(json(Map.of("liked", false))))
                .andExpect(jsonPath("$.data.liked").value(false))
                .andExpect(jsonPath("$.data.likeCount").value(1));
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.likedByMe").value(false));
    }

    @Test
    @DisplayName("내 프로필에 좋아요하거나 liked 를 빠뜨리면 400")
    void likeValidation() throws Exception {
        mvc.perform(patch("/community/profiles/{id}/like", me.getId()).with(as(me)).with(json(Map.of("liked", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(patch("/community/profiles/{id}/like", wanderer.id()).with(as(me)).with(json("{}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("'나도 탐색중'(COM-4-2): 등록하면 인원이 오르고, 조회하면 내 상태와 인원이 오며, 해제하면 내려간다")
    void exploringToggle() throws Exception {
        Long prague = hashtagId(wanderer.member(), "프라하");

        for (int i = 0; i < 2; i++) {
            explore(me, "HASHTAG", prague, true)
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.targetType").value("HASHTAG"))
                    .andExpect(jsonPath("$.data.targetId").value(prague))
                    .andExpect(jsonPath("$.data.exploring").value(true))
                    .andExpect(jsonPath("$.data.exploringCount").value(1));
        }
        explore(foodtrip, "HASHTAG", prague, true).andExpect(jsonPath("$.data.exploringCount").value(2));
        mvc.perform(get("/community/exploring").param("targetType", "HASHTAG").param("targetId", prague.toString())
                        .with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.exploring").value(true))
                .andExpect(jsonPath("$.data.exploringCount").value(2));

        explore(me, "HASHTAG", prague, false)
                .andExpect(jsonPath("$.data.exploring").value(false))
                .andExpect(jsonPath("$.data.exploringCount").value(1));
        mvc.perform(get("/community/exploring").param("targetType", "HASHTAG").param("targetId", prague.toString())
                        .with(as(me)))
                .andExpect(jsonPath("$.data.exploring").value(false))
                .andExpect(jsonPath("$.data.exploringCount").value(1));

        Long castle = nodeId(me, wanderer.member(), PRAGUE_CASTLE);
        explore(me, "NODE", castle, true)
                .andExpect(jsonPath("$.data.targetType").value("NODE"))
                .andExpect(jsonPath("$.data.exploringCount").value(1));
    }

    @Test
    @DisplayName("비공개 해시태그는 '나도 탐색중' 대상이 아니다 (404)")
    void cannotExploreHiddenHashtag() throws Exception {
        Long cafe = hashtagId(wanderer.member(), "카페");

        explore(me, "HASHTAG", cafe, true).andExpect(status().isNotFound());
        mvc.perform(get("/community/exploring").param("targetType", "HASHTAG").param("targetId", cafe.toString())
                .with(as(me))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("해시태그 여러 개를 골라 해시태그 묶음으로 북마크(COM-4-3 · 4-4)하면 201 과 bookmarkId 가 오고, 같은 선택을 또 저장해도 같은 북마크다")
    void bookmarkHashtagBundle() throws Exception {
        List<Long> tags = List.of(hashtagId(wanderer.member(), "프라하"), hashtagId(wanderer.member(), "야간열차"));

        Long bookmarkId = readLong(bookmark(me, "HASHTAG", tags, "HASHTAG_BUNDLE", wanderer.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value(201))
                .andExpect(jsonPath("$.data.bookmarkId").isNumber())
                .andExpect(jsonPath("$.data.targetIds").value(contains(ids(tags.toArray(Long[]::new)))))
                .andExpect(jsonPath("$.data.saveType").value("HASHTAG_BUNDLE")), "$.data.bookmarkId");

        bookmark(me, "HASHTAG", tags, "HASHTAG_BUNDLE", wanderer.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookmarkId").value(bookmarkId));
    }

    @Test
    @DisplayName("노드를 골라 미니 마인드맵으로 북마크하려면 sourceMindmapId(작성자 id)가 맞아야 한다")
    void bookmarkMiniMindmap() throws Exception {
        List<Long> nodes = List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE),
                nodeId(me, wanderer.member(), "https://www.prague.eu/en"));

        bookmark(me, "NODE", nodes, "MINI_MINDMAP", wanderer.id())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.saveType").value("MINI_MINDMAP"));
        bookmark(me, "NODE", nodes, "MINI_MINDMAP", null).andExpect(status().isBadRequest());
        bookmark(me, "NODE", nodes, "MINI_MINDMAP", me.getId()).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("비공개 해시태그가 하나라도 섞인 선택은 북마크되지 않는다 (404, 아무것도 저장 안 됨)")
    void cannotBookmarkHiddenHashtag() throws Exception {
        Long prague = hashtagId(wanderer.member(), "프라하");
        Long cafe = hashtagId(wanderer.member(), "카페");

        bookmark(me, "HASHTAG", List.of(cafe), "HASHTAG_BUNDLE", null).andExpect(status().isNotFound());
        bookmark(me, "HASHTAG", List.of(prague, cafe), "HASHTAG_BUNDLE", null).andExpect(status().isNotFound());

        mvc.perform(get("/community/me/bookmarks/hashtag-bundles").with(as(me)))
                .andExpect(jsonPath("$.data.bundles", hasSize(0)));
    }

    @Test
    @DisplayName("선택이 비었거나 같은 대상을 두 번 넣거나 저장 방식을 빠뜨리면 400")
    void bookmarkSelectionValidation() throws Exception {
        Long prague = hashtagId(wanderer.member(), "프라하");

        bookmark(me, "HASHTAG", List.of(), "HASHTAG_BUNDLE", null).andExpect(status().isBadRequest());
        bookmark(me, "HASHTAG", List.of(prague, prague), "HASHTAG_BUNDLE", null).andExpect(status().isBadRequest());
        bookmark(me, "HASHTAG", List.of(prague), null, null).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("팔로우 버튼: 누르면 팔로잉이 되어 프로필에 보이고, 다시 누르면 풀린다")
    void follow() throws Exception {
        mvc.perform(patch("/community/profiles/{id}/follow", wanderer.id()).with(as(me)).with(json(Map.of("following", true))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.following").value(true));
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.following").value(true));
        mvc.perform(patch("/community/profiles/{id}/follow", wanderer.id()).with(as(me)).with(json(Map.of("following", false))))
                .andExpect(jsonPath("$.data.following").value(false));
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.following").value(false));
    }

    @Test
    @DisplayName("공유 세션 카드에 FE SessionCard 가 읽는 제목 · 시작/종료 시각 · 페이지 수가 온다")
    void sharedSessionCardFields() throws Exception {
        showContent();

        mvc.perform(get("/community/profiles/{id}/shared-sessions", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[0].title").value("프라하 1박 일정 짜기"))
                .andExpect(jsonPath("$.data.sessions[0].startedAt").value("2026-04-20T23:05:00"))
                .andExpect(jsonPath("$.data.sessions[0].endedAt").value("2026-04-20T23:32:00"))
                .andExpect(jsonPath("$.data.sessions[0].pageCount").value(6));
    }

    @Test
    @DisplayName("비공개 해시태그가 섞인 공유 세션은 그 태그만 빠진 채 세션 탭에 남는다")
    void mixedTagSessionStaysWithoutHiddenTag() throws Exception {
        mvc.perform(get("/community/profiles/{id}/shared-sessions", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(
                        ids(wanderer.prague(), wanderer.cafe(), wanderer.nightjet(), wanderer.vienna()))))
                .andExpect(jsonPath("$.data.sessions[1].tags").value(contains("비엔나")));
    }

    @Test
    @DisplayName("커뮤니티 API 오류 문구도 한국어로 온다")
    void communityErrorsAreKorean() throws Exception {
        String korean = ".*[가-힣].*";
        Long cafe = hashtagId(wanderer.member(), "카페");
        Long prague = hashtagId(wanderer.member(), "프라하");

        mvc.perform(patch("/community/profiles/{id}/like", me.getId()).with(as(me)).with(json(Map.of("liked", true))))
                .andExpect(jsonPath("$.message").value(matchesPattern(korean)));
        mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).param("hashtagId", cafe.toString()).with(as(me)))
                .andExpect(jsonPath("$.message").value(matchesPattern(korean)));
        bookmark(me, "HASHTAG", List.of(prague, prague), "HASHTAG_BUNDLE", null)
                .andExpect(jsonPath("$.message").value(matchesPattern(korean)));
    }
}
