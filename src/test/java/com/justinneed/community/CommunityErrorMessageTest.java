package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.justinneed.community.bookmark.BookmarkCommandService;
import com.justinneed.community.bookmark.SaveType;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.global.exception.ErrorCode;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * FE 는 오류 message 를 그대로 토스트로 보여준다. 한글이 없는 문구가 나가면 안 된다.
 * Bean Validation 기본 문구는 요청 언어를 따르므로 Accept-Language 를 en 으로 고정해 영어로 새는지 본다.
 */
class CommunityErrorMessageTest extends CommunityTestSupport {
    private static final String KOREAN = ".*[가-힣].*";

    @Autowired PublicGraphService graphs;
    @Autowired BookmarkCommandService bookmarks;

    private ResultActions send(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", auth(viewerId)).header("Accept-Language", "en"));
    }

    private ResultActions send(HttpMethod method, String path, String body) throws Exception {
        return send(request(method, path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static void expectKorean400(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.message").value(matchesPattern(KOREAN)));
    }

    @ParameterizedTest
    @MethodSource("badQueries")
    void badQueryParametersAreKorean400(String path) throws Exception {
        expectKorean400(send(get(path)));
    }

    static Stream<String> badQueries() {
        return Stream.of(
                // 필수 값 누락
                "/community/search/profiles",
                "/community/search/mindmaps",
                "/community/search/hashtags",
                "/community/exploring",
                "/community/exploring?targetType=HASHTAG",
                "/community/exploring?targetId=1",
                "/community/exploring?targetType=&targetId=1",
                "/community/exploring?targetType=HASHTAG&targetId=",
                // 타입 · enum 불일치
                "/community/exploring?targetType=BOGUS&targetId=1",
                "/community/exploring?targetType=hashtag&targetId=1",
                "/community/exploring?targetType=HASHTAG&targetId=abc",
                "/community/exploring?targetType=HASHTAG&targetId=99999999999999999999",
                "/community/recommendations/hashtags/trending?period=BOGUS",
                "/community/recommendations/hashtags/trending?limit=abc",
                "/community/recommendations/mindmaps/interests?limit=abc",
                "/community/recommendations/mindmaps/recent-bookmarks?limit=abc",
                "/community/me/bookmarks/mindmaps?page=abc",
                "/community/me/bookmarks/hashtag-bundles?size=abc",
                "/community/search/profiles?q=java&page=abc",
                "/community/search/hashtags?q=java&limit=abc",
                "/community/profiles/abc",
                "/community/profiles/1/mindmap?hashtagId=abc",
                // 범위 밖
                "/community/exploring?targetType=HASHTAG&targetId=0",
                "/community/recommendations/mindmaps/interests?limit=0",
                "/community/recommendations/mindmaps/recent-bookmarks?limit=101",
                "/community/recommendations/hashtags/trending?limit=0",
                "/community/me/bookmarks/mindmaps?page=-1",
                "/community/me/bookmarks/hashtag-bundles?size=101",
                "/community/search/profiles?q=java&size=0",
                "/community/search/hashtags?q=java&limit=21");
    }

    @ParameterizedTest
    @MethodSource("badBodies")
    void badBodiesAreKorean400(HttpMethod method, String path, String body) throws Exception {
        expectKorean400(send(method, path, body));
    }

    static Stream<Arguments> badBodies() {
        return Stream.of(
                Arguments.of(HttpMethod.PATCH, "/community/profiles/1/like", "{}"),
                Arguments.of(HttpMethod.PATCH, "/community/profiles/0/like", "{\"liked\":true}"),
                Arguments.of(HttpMethod.PATCH, "/community/exploring", "{}"),
                Arguments.of(HttpMethod.PATCH, "/community/exploring",
                        "{\"targetType\":\"HASHTAG\",\"targetId\":0,\"exploring\":true}"),
                Arguments.of(HttpMethod.PATCH, "/community/exploring",
                        "{\"targetType\":\"BOGUS\",\"targetId\":1,\"exploring\":true}"),
                Arguments.of(HttpMethod.POST, "/community/bookmarks", "{}"),
                Arguments.of(HttpMethod.POST, "/community/bookmarks",
                        "{\"targetType\":\"HASHTAG\",\"targetIds\":[],\"saveType\":\"HASHTAG_BUNDLE\"}"),
                Arguments.of(HttpMethod.POST, "/community/bookmarks",
                        "{\"targetType\":\"HASHTAG\",\"targetIds\":[null],\"saveType\":\"HASHTAG_BUNDLE\"}"),
                Arguments.of(HttpMethod.POST, "/community/bookmarks",
                        "{\"targetType\":\"HASHTAG\",\"targetIds\":[0],\"saveType\":\"HASHTAG_BUNDLE\"}"),
                Arguments.of(HttpMethod.POST, "/community/bookmarks",
                        "{\"targetType\":\"HASHTAG\",\"targetIds\":[1],\"saveType\":\"HASHTAG_BUNDLE\",\"sourceMindmapId\":0}"),
                Arguments.of(HttpMethod.POST, "/community/bookmarks",
                        "{\"targetType\":\"HASHTAG\",\"targetIds\":[1,1],\"saveType\":\"HASHTAG_BUNDLE\"}"),
                Arguments.of(HttpMethod.POST, "/community/bookmarks",
                        "{\"targetType\":\"HASHTAG\",\"targetIds\":[1],\"saveType\":\"BOGUS\"}"),
                Arguments.of(HttpMethod.PATCH, "/community/me/hashtags/visibility", "{}"),
                Arguments.of(HttpMethod.PATCH, "/community/me/hashtags/1/visibility", "{}"),
                Arguments.of(HttpMethod.POST, "/community/me/pinned-sessions", "{}"),
                Arguments.of(HttpMethod.PATCH, "/community/me/pinned-sessions/order", "{}"),
                Arguments.of(HttpMethod.POST, "/community/me/interests", "{}"),
                Arguments.of(HttpMethod.POST, "/community/me/shared-sessions", "{}"),
                Arguments.of(HttpMethod.POST, "/community/me/shared-sessions", "{\"sessionId\":0}"),
                Arguments.of(HttpMethod.PATCH, "/community/me/shared-sessions/order", "{}"),
                Arguments.of(HttpMethod.PATCH, "/community/me/shared-sessions/order", "{\"sessionIds\":[0]}"),
                Arguments.of(HttpMethod.PATCH, "/community/me/visibility", "{}"));
    }

    @Test
    void selfLikeIsKorean() throws Exception {
        expectKorean400(mvc.perform(patch("/community/profiles/{id}/like", ownerId).header("Authorization", auth(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":true}")));
    }

    @Test
    void bookmarkRulesAreKorean() throws Exception {
        session(ownerId, true, "Java");
        var graph = graphs.graph(ownerId, null);
        var tag = graph.hashtags().get(0).hashtagId();
        var node = graph.nodes().get(0).nodeId();
        // 미니 마인드맵인데 sourceMindmapId 없음
        expectKorean400(send(HttpMethod.POST, "/community/bookmarks",
                "{\"targetType\":\"NODE\",\"targetIds\":[" + node + "],\"saveType\":\"MINI_MINDMAP\"}"));
        // 묶음의 sourceMindmapId 가 대상과 다름
        expectKorean400(send(HttpMethod.POST, "/community/bookmarks", "{\"targetType\":\"HASHTAG\",\"targetIds\":[" + tag
                + "],\"saveType\":\"HASHTAG_BUNDLE\",\"sourceMindmapId\":" + viewerId + "}"));
    }

    @Test
    void originalOfBundleIsKorean() throws Exception {
        session(ownerId, true, "Java");
        var tag = graphs.graph(ownerId, null).hashtags().get(0).hashtagId();
        var saved = bookmarks.save(viewerId, TargetType.HASHTAG, List.of(tag), SaveType.HASHTAG_BUNDLE, null, null);
        expectKorean400(send(get("/community/me/bookmarks/{id}/original", saved.bookmarkId())));
    }

    @Test
    void everyErrorCodeIsKorean() {
        assertThat(ErrorCode.values()).allSatisfy(code -> assertThat(code.getMessage()).matches(KOREAN));
    }

    @Test
    void missingResourceIsKorean404() throws Exception {
        send(get("/community/exploring?targetType=HASHTAG&targetId=999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(matchesPattern(KOREAN)));
    }
}
