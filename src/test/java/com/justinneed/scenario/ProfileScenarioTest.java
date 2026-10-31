package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.BrowsingSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 마이프로필 (/profile) + 프로필 수정 화면.
 * FE: src/features/profile/ProfileView.tsx · ProfileEdit.tsx, 목 MY_PROFILE(pinnedSessionIds [9001, 9003, 9002, 9005]).
 * FE NodeVisibility 'url' | 'full' ↔ BE URL_ONLY | CONTENT 로 바꿔 보내야 한다.
 */
@DisplayName("마이프로필 · 프로필 수정 화면")
class ProfileScenarioTest extends ScenarioTestSupport {

    private static final String SESSION_NOT_FOUND = "세션을 찾을 수 없습니다.";

    private ResultActions pin(Member me, BrowsingSession session) throws Exception {
        return mvc.perform(post("/community/me/pinned-sessions").with(as(me)).with(json(Map.of("sessionId", session.getId()))));
    }

    private Long addInterest(Member me, String hashtag) throws Exception {
        return readLong(mvc.perform(post("/community/me/interests").with(as(me)).with(json(Map.of("hashtag", hashtag))))
                .andExpect(status().isCreated()), "$.data.interestId");
    }

    @Test
    @DisplayName("프로필 헤더: 닉네임 · 노드 수 · 상위 5개 해시태그(노드 수 많은 순) · 대표 해시태그 · 좋아요 · 공유 세션이 한 번에 온다")
    void myProfileHeader() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        pin(me, mock.rome()).andExpect(status().isCreated());
        pin(me, mock.paris()).andExpect(status().isCreated());
        for (String interest : List.of("유럽여행", "HCI", "미술관")) {
            addInterest(me, interest);
        }

        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.userId").value(me.getId()))
                .andExpect(jsonPath("$.data.nickname").value("sprout"))
                // 공개 세션 출처 수: 로마 4 + 파리 2
                .andExpect(jsonPath("$.data.totalNodeCount").value(6))
                .andExpect(jsonPath("$.data.topHashtags[*].name").value(contains("로마", "먹거리", "바티칸", "콜로세움", "루브르")))
                .andExpect(jsonPath("$.data.topHashtags[0].nodeCount").value(4))
                .andExpect(jsonPath("$.data.topHashtags[0].hashtagId").isNumber())
                .andExpect(jsonPath("$.data.interests").value(contains("유럽여행", "HCI", "미술관")))
                .andExpect(jsonPath("$.data.likeCount").value(0))
                .andExpect(jsonPath("$.data.likedByMe").value(false))
                // 통계 '공유 세션 수' = pinnedSessions 길이
                .andExpect(jsonPath("$.data.pinnedSessions[*].id").value(contains(ids(mock.rome(), mock.paris()))));
    }

    @Test
    @DisplayName("대표(관심) 해시태그 추가(COM-1-7): 201 과 interestId · 개수가 오고, 프로필에는 추가한 순서대로 보인다")
    void addInterests() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);

        mvc.perform(post("/community/me/interests").with(as(me)).with(json(Map.of("hashtag", "유럽여행"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.code").value(201))
                .andExpect(jsonPath("$.data.interestId").isNumber())
                .andExpect(jsonPath("$.data.hashtag").value("유럽여행"))
                .andExpect(jsonPath("$.data.interestCount").value(1));
        mvc.perform(post("/community/me/interests").with(as(me)).with(json(Map.of("hashtag", "HCI"))))
                .andExpect(jsonPath("$.data.interestCount").value(2));

        // 수정 화면 목록(id 포함)은 순서를 보장하지 않으니 FE 가 프로필 순서를 따로 쓴다
        mvc.perform(get("/community/me/interests").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[*].hashtag").value(containsInAnyOrder("유럽여행", "HCI")))
                .andExpect(jsonPath("$.data[*].interestId", everyItem(notNullValue())));
        mvc.perform(get("/community/profiles/{id}/interests", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data").value(contains("유럽여행", "HCI")));
    }

    @Test
    @DisplayName("관심 해시태그를 '#' 붙여 보내면 400 형식 안내, 대소문자만 다른 중복이면 400 '이미 등록된 해시태그입니다.'")
    void interestValidation() throws Exception {
        Member me = member("sprout");
        addInterest(me, "HCI");

        mvc.perform(post("/community/me/interests").with(as(me)).with(json(Map.of("hashtag", "#유럽"))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST,
                        "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다."));
        mvc.perform(post("/community/me/interests").with(as(me)).with(json(Map.of("hashtag", "hci"))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "이미 등록된 해시태그입니다."));
    }

    @Test
    @DisplayName("관심 해시태그 삭제(COM-1-8)는 interestId 로 하고, 관심이 아닌 해시태그 id 면 404 '관심 해시태그를 찾을 수 없습니다.'")
    void deleteInterestById() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);
        Long europe = addInterest(me, "유럽여행");
        addInterest(me, "HCI");

        mvc.perform(delete("/community/me/interests/{id}", europe).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.interestId").value(europe));
        mvc.perform(get("/community/me/interests").with(as(me)))
                .andExpect(jsonPath("$.data[*].hashtag").value(contains("HCI")));

        Long sessionTagOnly = hashtagId(me, "로마");
        mvc.perform(delete("/community/me/interests/{id}", sessionTagOnly).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "관심 해시태그를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("노드 공개 범위(COM-1-1): 기본은 URL_ONLY, CONTENT 로 바꾸면 저장되고 FE 값('full')을 그대로 보내면 400")
    void nodeVisibilityScope() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/community/me/visibility").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.visibilityScope").value("URL_ONLY"));
        mvc.perform(patch("/community/me/visibility").with(as(me)).with(json(Map.of("visibility", "CONTENT"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.visibilityScope").value("CONTENT"))
                .andExpect(jsonPath("$.data.updatedAt").value(notNullValue()));
        mvc.perform(get("/community/me/visibility").with(as(me)))
                .andExpect(jsonPath("$.data.visibilityScope").value("CONTENT"));
        // 응답 필드 이름(visibilityScope)으로 보내도 받는다
        mvc.perform(patch("/community/me/visibility").with(as(me)).with(json(Map.of("visibilityScope", "URL_ONLY"))))
                .andExpect(jsonPath("$.data.visibilityScope").value("URL_ONLY"));
        mvc.perform(patch("/community/me/visibility").with(as(me)).with(json(Map.of("visibility", "full"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("해시태그별 공개 설정(COM-1-2): 목록에 hashtagId · 공개 여부가 오고, 비공개로 바꾸면 다른 사람 마인드맵에서 그 태그만 빠진다")
    void hashtagVisibility() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        Member viewer = member("foodtrip");
        mvc.perform(patch("/sessions/{id}", mock.rome().getId()).with(as(me)).with(json(Map.of("isPublic", true))))
                .andExpect(okEnvelope());
        Long rome = hashtagId(me, "로마");

        mvc.perform(get("/community/me/hashtags").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].isPublic").value(contains(true)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].hashtagId").value(contains(rome.intValue())));
        mvc.perform(get("/community/profiles/{id}/mindmap", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(hasItem("로마")))
                .andExpect(jsonPath("$.data.nodes", hasSize(4)));

        mvc.perform(patch("/community/me/hashtags/{id}/visibility", rome).with(as(me)).with(json(Map.of("isPublic", false))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.hashtagId").value(rome))
                .andExpect(jsonPath("$.data.hashtag").value("로마"))
                .andExpect(jsonPath("$.data.isPublic").value(false));

        mvc.perform(get("/community/me/hashtags").with(as(me)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].isPublic").value(contains(false)));
        // 로마 세션은 다른 태그(콜로세움 등)로 계속 보인다
        mvc.perform(get("/community/profiles/{id}/mindmap", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(not(hasItem("로마"))))
                .andExpect(jsonPath("$.data.nodes", hasSize(4)));
    }

    @Test
    @DisplayName("공유 세션 핀고정(COM-1-3): 201 과 순서(order)가 오고 세션이 공개로 바뀌며, 다른 사람에게 핀 순서대로 보인다")
    void pinSessions() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        Member viewer = member("foodtrip");

        // FE 목 MY_PROFILE.pinnedSessionIds = [9001 로마, 9003 파리, 9002 항공권, 9005 바르셀로나]
        List<BrowsingSession> pins = List.of(mock.rome(), mock.paris(), mock.flight(), mock.barca());
        for (int order = 0; order < pins.size(); order++) {
            pin(me, pins.get(order))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.code").value(201))
                    .andExpect(jsonPath("$.data.sessionId").value(pins.get(order).getId()))
                    .andExpect(jsonPath("$.data.isPublic").value(true))
                    .andExpect(jsonPath("$.data.isPinned").value(true))
                    .andExpect(jsonPath("$.data.order").value(order));
        }

        mvc.perform(get("/sessions/{id}", mock.flight().getId()).with(as(me)))
                .andExpect(jsonPath("$.data.isPublic").value(true));
        mvc.perform(get("/community/profiles/{id}/shared-sessions", me.getId()).with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(mock.rome(), mock.paris(), mock.flight(), mock.barca()))));
    }

    @Test
    @DisplayName("공유 세션 순서 변경(COM-1-5): 핀고정한 세션 id 를 전부 보내면 그 순서가 되고, 빠지거나 남으면 400")
    void reorderPins() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        Member viewer = member("foodtrip");
        for (BrowsingSession session : List.of(mock.rome(), mock.paris(), mock.flight(), mock.barca())) {
            pin(me, session).andExpect(status().isCreated());
        }
        List<Long> next = List.of(mock.flight().getId(), mock.rome().getId(), mock.barca().getId(), mock.paris().getId());

        mvc.perform(patch("/community/me/pinned-sessions/order").with(as(me)).with(json(Map.of("sessionIds", next))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessionIds").value(contains(ids(next.toArray(Long[]::new)))));
        mvc.perform(get("/community/profiles/{id}/shared-sessions", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(next.toArray(Long[]::new)))));

        // 오류 문구는 태그 그룹과 같은 '정렬할 그룹 목록이 올바르지 않습니다.' 를 쓴다
        mvc.perform(patch("/community/me/pinned-sessions/order").with(as(me))
                        .with(json(Map.of("sessionIds", List.of(mock.rome().getId(), mock.paris().getId())))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "정렬할 그룹 목록이 올바르지 않습니다."));
        mvc.perform(patch("/community/me/pinned-sessions/order").with(as(me)).with(json(Map.of("sessionIds",
                        List.of(mock.flight().getId(), mock.rome().getId(), mock.barca().getId(), mock.paris().getId(),
                                mock.prague().getId())))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "정렬할 그룹 목록이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("핀고정 해제(COM-1-4): 세션이 비공개로 바뀌고 공유 목록 · 다른 사람 마인드맵에서 빠지며 나머지 순서는 그대로다")
    void unpinMakesPrivate() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        Member viewer = member("foodtrip");
        for (BrowsingSession session : List.of(mock.rome(), mock.paris(), mock.flight())) {
            pin(me, session).andExpect(status().isCreated());
        }

        mvc.perform(delete("/community/me/pinned-sessions/{id}", mock.rome().getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessionId").value(mock.rome().getId()))
                .andExpect(jsonPath("$.data.isPublic").value(false))
                .andExpect(jsonPath("$.data.isPinned").value(false))
                .andExpect(jsonPath("$.data.order").value(nullValue()));

        mvc.perform(get("/sessions/{id}", mock.rome().getId()).with(as(me)))
                .andExpect(jsonPath("$.data.isPublic").value(false));
        mvc.perform(get("/community/profiles/{id}/shared-sessions", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(mock.paris(), mock.flight()))));
        mvc.perform(get("/community/profiles/{id}/mindmap", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.nodes[*].sourceUrl").value(not(hasItem("https://www.colosseo.it/en/"))));
    }

    @Test
    @DisplayName("핀고정 안 한 세션을 해제하거나 · 남의 세션 · 삭제한 세션을 핀고정하면 404 '세션을 찾을 수 없습니다.'")
    void pinErrors() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        Wanderer wanderer = seedWanderer();
        mvc.perform(delete("/sessions/{id}", mock.rail().getId()).with(as(me))).andExpect(okEnvelope());

        mvc.perform(delete("/community/me/pinned-sessions/{id}", mock.prague().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        pin(me, wanderer.vienna()).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        pin(me, mock.rail()).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("세션 상세에서 공개를 끄면 핀고정했던 세션도 공유 목록에서 빠진다 (두 공개 경로가 같은 isPublic 을 쓴다)")
    void privateToggleRemovesPinnedSession() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        pin(me, mock.rome()).andExpect(status().isCreated());
        pin(me, mock.paris()).andExpect(status().isCreated());

        mvc.perform(patch("/sessions/{id}", mock.rome().getId()).with(as(me)).with(json(Map.of("isPublic", false))))
                .andExpect(okEnvelope());

        mvc.perform(get("/community/profiles/{id}/shared-sessions", me.getId()).with(as(me)))
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(mock.paris()))));
    }

    @Test
    @DisplayName("로그인하지 않으면 프로필 · 프로필 수정 API 는 401")
    void requiresLogin() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/community/profiles/{id}", me.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/community/me/visibility")).andExpect(status().isUnauthorized());
        mvc.perform(post("/community/me/pinned-sessions").with(json(Map.of("sessionId", 1))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("소개(bio) 수정: 120자까지 저장돼 프로필 헤더에 보이고, 120자를 넘으면 400")
    void updateBio() throws Exception {
        Member me = member("sprout");
        Member viewer = member("foodtrip");
        String bio = "HCI 연구하며 여행 다니는 사람. 요즘은 유럽.";

        mvc.perform(patch("/community/me/profile").with(as(me)).with(json(Map.of("bio", bio))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bio").value(bio));
        mvc.perform(get("/community/profiles/{id}", me.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.bio").value(bio));
        mvc.perform(patch("/community/me/profile").with(as(me)).with(json(Map.of("bio", "가".repeat(121)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("프로필 수정의 해시태그 목록에 공개 여부와 함께 노드 수가 온다 (비공개 태그 포함)")
    void myHashtagsHaveCounts() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        pin(me, mock.rome()).andExpect(status().isCreated());
        mvc.perform(patch("/community/me/hashtags/{id}/visibility", hashtagId(me, "먹거리")).with(as(me))
                .with(json(Map.of("isPublic", false)))).andExpect(okEnvelope());

        mvc.perform(get("/community/me/hashtags").with(as(me)))
                // 노드 수는 AI 노드 기준이라 분석 결과가 없는 이 세션들은 0 이다 (값이 있는 경우는 MyProfileScenarioTest)
                .andExpect(jsonPath("$.data[?(@.hashtag == '로마')].nodeCount").value(contains(0)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '먹거리')].nodeCount").value(contains(0)))
                .andExpect(jsonPath("$.data[?(@.hashtag == '먹거리')].isPublic").value(contains(false)));
    }
}
