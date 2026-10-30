package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;

/**
 * 탈퇴한 회원: 본인 토큰은 기기와 상관없이 401 이고, 다른 사람 화면에서는 없는 회원으로 보인다.
 * 다른 기기 토큰이나 서버 재시작 등으로 무효화 목록에 없는 토큰도 인증 필터에서 막혀야 한다.
 */
@DisplayName("탈퇴한 회원")
class WithdrawnMemberScenarioTest extends ScenarioTestSupport {

    private static final String MEMBER_NOT_FOUND = "회원을 찾을 수 없습니다.";
    private static final String PRAGUE_CASTLE = "https://www.hrad.cz/en/prague-castle-for-visitors";
    private static final String PRAGUE_EU = "https://www.prague.eu/en";

    @Autowired
    private TagGroupRepository tagGroups;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    private Member me;
    private Wanderer wanderer;
    private Member foodtrip;

    @BeforeEach
    void seed() {
        me = member("sprout");
        wanderer = seedWanderer();
        foodtrip = member("foodtrip");
        session(foodtrip, "파리 미식 지도", "2026-04-26T12:00:00", "2026-04-26T13:30:00", 20, false, true,
                List.of("파리", "미슐랭"),
                List.of(new Source("파리 관광청", "https://www.parisinfo.com/", "마레 지구 산책 코스.")),
                "# 파리 미식");
    }

    private void withdraw(Member member) throws Exception {
        mvc.perform(delete("/settings/account").with(as(member)).with(json(Map.of("confirmText", "WITHDRAW"))))
                .andExpect(okEnvelope());
    }

    /** 탈퇴 때 쓰지 않은 토큰. 유효 시간을 달리해 만들어 jti 없이도 무효화 목록에 든 토큰과 달라지게 한다. */
    private String otherDeviceToken(Member member) {
        return new JwtTokenProvider(jwtSecret, 1800, 1800).createAccessToken(member.getId());
    }

    private Long saveBundle(Member owner, String... hashtags) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String hashtag : hashtags) {
            ids.add(hashtagId(owner, hashtag));
        }
        return saveBundle(ids);
    }

    private Long saveBundle(List<Long> hashtagIds) throws Exception {
        return readLong(mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of(
                        "targetType", "HASHTAG", "targetIds", hashtagIds, "saveType", "HASHTAG_BUNDLE"))))
                .andExpect(status().isCreated()), "$.data.bookmarkId");
    }

    @Test
    @DisplayName("탈퇴 후 같은 회원 id 로 새로 만든 access 토큰(서버 재시작으로 무효화 목록을 잃은 상황)으로도 내 정보 · 닉네임 변경은 401")
    void withdrawnMemberIsUnauthorizedEvenWithFreshToken() throws Exception {
        withdraw(me);
        String freshToken = otherDeviceToken(me);

        mvc.perform(get("/members/me").with(bearerToken(freshToken)))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/members/me/nickname").with(bearerToken(freshToken))
                        .with(json(Map.of("nickname", "comeback"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/community/me/hashtags").with(bearerToken(freshToken)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("웹에서 탈퇴하면 휴대폰에 남은 access 토큰도 모든 보호 API 에서 401 이고 그룹도 못 만든다")
    void otherDeviceTokenIsRejectedAfterWithdrawal() throws Exception {
        String phone = otherDeviceToken(me);
        String web = jwt.createAccessToken(me.getId());
        List<String> protectedPaths = List.of("/members/me", "/sessions", "/sessions/hashtags", "/tag-groups",
                "/settings/integrations", "/settings/collection/excluded-urls");
        for (String path : protectedPaths) {
            mvc.perform(get(path).with(bearerToken(phone))).andExpect(okEnvelope());
        }

        mvc.perform(delete("/settings/account").with(bearerToken(web))
                        .with(json(Map.of("confirmText", "WITHDRAW"))))
                .andExpect(okEnvelope());

        for (String path : protectedPaths) {
            mvc.perform(get(path).with(bearerToken(phone))).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/tag-groups").with(bearerToken(phone))
                        .with(json(Map.of("name", "탈퇴 후", "hashtags", List.of("로마")))))
                .andExpect(status().isUnauthorized());
        assertThat(tagGroups.findByUserIdOrderByPositionAscIdAsc(me.getId())).isEmpty();
    }

    @Test
    @DisplayName("회원 행이 없는 id 의 토큰은 탈퇴로 보지 않아 지금처럼 컨트롤러까지 간다")
    void tokenWithoutMemberRowReachesControllers() throws Exception {
        String noRow = jwt.createAccessToken(999_999L);

        mvc.perform(get("/sessions").with(bearerToken(noRow))).andExpect(okEnvelope());
        mvc.perform(get("/tag-groups").with(bearerToken(noRow))).andExpect(okEnvelope());
        mvc.perform(get("/members/me").with(bearerToken(noRow)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
    }

    @Test
    @DisplayName("탈퇴한 회원의 프로필 · 마인드맵 · 공유 세션 · 관심 해시태그 · 세션 상세를 열거나 좋아요를 누르면 404")
    void withdrawnProfileIsNotFound() throws Exception {
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me))).andExpect(okEnvelope());

        withdraw(wanderer.member());

        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        mvc.perform(get("/community/profiles/{id}/shared-sessions", wanderer.id()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        mvc.perform(get("/community/profiles/{id}/interests", wanderer.id()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        mvc.perform(get("/community/profiles/{id}/nodes", wanderer.id()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        mvc.perform(get("/community/sessions/{id}", wanderer.prague().getId()).with(as(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(patch("/community/profiles/{id}/like", wanderer.id()).with(as(me))
                        .with(json(Map.of("liked", true))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("탈퇴한 회원은 해시태그 검색(프로필 · 마인드맵)과 관심 해시태그 추천에 더 이상 안 나온다")
    void withdrawnMemberIsHiddenFromSearchAndRecommendations() throws Exception {
        mvc.perform(post("/community/me/interests").with(as(me)).with(json(Map.of("hashtag", "프라하"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/community/search/profiles").param("hashtag", "프라하").with(as(me)))
                .andExpect(jsonPath("$.data.profiles[*].userId").value(hasItem(wanderer.id().intValue())));
        mvc.perform(get("/community/recommendations/mindmaps/interests").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].ownerId").value(contains(ids(wanderer.id()))));

        withdraw(wanderer.member());

        // 관심 해시태그로 프라하를 단 나(sprout)만 남는다
        mvc.perform(get("/community/search/profiles").param("hashtag", "프라하").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(ids(me.getId()))));
        mvc.perform(get("/community/search/mindmaps").param("hashtag", "프라하").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        mvc.perform(get("/community/recommendations/mindmaps/interests").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.basisHashtags").value(contains("프라하")))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        // 다른 회원은 그대로 검색된다
        mvc.perform(get("/community/search/profiles").param("hashtag", "미슐랭").with(as(me)))
                .andExpect(jsonPath("$.data.profiles[*].userId").value(contains(ids(foodtrip.getId()))));
    }

    @Test
    @DisplayName("탈퇴한 회원의 해시태그는 내 해시태그 묶음 북마크 · 최근 저장 기반 추천에서 빠진다 (다 빠진 묶음은 목록에서 사라짐)")
    void withdrawnOwnersHashtagsDisappearFromBookmarks() throws Exception {
        Long mixed = saveBundle(List.of(hashtagId(wanderer.member(), "프라하"), hashtagId(foodtrip, "미슐랭")));
        saveBundle(wanderer.member(), "비엔나");
        mvc.perform(get("/community/me/bookmarks/hashtag-bundles").with(as(me)))
                .andExpect(jsonPath("$.data.bundles", hasSize(2)));

        withdraw(wanderer.member());

        mvc.perform(get("/community/me/bookmarks/hashtag-bundles").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(mixed))))
                .andExpect(jsonPath("$.data.bundles[0].hashtags").value(contains("미슐랭")));
        mvc.perform(get("/community/recommendations/mindmaps/recent-bookmarks").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.basisHashtags").value(contains("미슐랭")))
                .andExpect(jsonPath("$.data.basisHashtags").value(not(hasItem("프라하"))));
    }

    @Test
    @DisplayName("탈퇴한 회원의 마인드맵에서 저장한 미니 마인드맵은 목록에서 빠지고, '원래 마인드맵에서 보기'는 sourceAvailable=false")
    void miniMindmapOfWithdrawnOwnerIsUnavailable() throws Exception {
        List<Long> nodes = List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE), nodeId(me, wanderer.member(), PRAGUE_EU));
        Long bookmarkId = readLong(mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of(
                        "targetType", "NODE", "targetIds", nodes, "saveType", "MINI_MINDMAP",
                        "sourceMindmapId", wanderer.id()))))
                .andExpect(status().isCreated()), "$.data.bookmarkId");

        withdraw(wanderer.member());

        mvc.perform(get("/community/me/bookmarks/mindmaps").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sourceAvailable").value(false))
                .andExpect(jsonPath("$.data.mindmap").value(nullValue()))
                .andExpect(jsonPath("$.data.highlightedNodeIds", hasSize(0)));
    }
}
