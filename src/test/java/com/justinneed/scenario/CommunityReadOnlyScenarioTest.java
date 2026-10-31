package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.justinneed.auth.domain.Member;
import com.justinneed.community.SqlCapture;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 커뮤니티 GET 은 어떤 경로든 쓰기 · 잠금 SQL 을 보내지 않는다 (시드가 없는 회원의 프로필 행도 만들지 않는다). */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:community-read-only;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.justinneed.community.SqlCapture"
})
@DisplayName("커뮤니티 GET 은 읽기만 한다")
class CommunityReadOnlyScenarioTest extends ScenarioTestSupport {

    @Autowired
    private EntityManager entityManager;

    private Member owner;
    private Member viewer;
    private BrowsingSession prague;

    private void seed() {
        owner = member("owner");
        viewer = member("viewer");
        prague = session(owner, "프라하", "2026-04-20T23:05:00", "2026-04-20T23:32:00", 2, false, true,
                List.of("프라하", "체코"), List.of(new Source("프라하 성", "https://example.com/castle", "발췌")), "본문");
        CommunityProfile interests = new CommunityProfile(viewer.getId());
        interests.replaceInterests(List.of("프라하"));
        communityProfiles.saveAndFlush(interests);
        entityManager.flush();
        entityManager.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/community/profiles/{owner}",
            "/community/profiles/{owner}/mindmap",
            "/community/profiles/{owner}/nodes",
            "/community/profiles/{owner}/interests",
            "/community/profiles/{owner}/shared-sessions",
            "/community/search/profiles?hashtag=프라하",
            "/community/search/mindmaps?hashtag=프라하",
            "/community/recommendations/mindmaps/interests",
            "/community/recommendations/mindmaps/recent-bookmarks",
            "/community/recommendations/hashtags/trending",
            "/community/me/hashtags",
            "/community/me/interests",
            "/community/me/visibility",
            "/community/me/bookmarks/mindmaps",
            "/community/me/bookmarks/hashtag-bundles"
    })
    @DisplayName("쓰기 · 잠금 SQL 이 없다")
    void noWriteOrLock(String path) throws Exception {
        seed();
        SqlCapture.clear();

        mvc.perform(get(path.replace("{owner}", owner.getId().toString())).with(as(viewer)));

        assertThat(SqlCapture.all()).isNotEmpty();
        assertThat(SqlCapture.writesAndLocks()).isEmpty();
    }

    @Test
    @DisplayName("나도 탐색중 상태 조회는 작성자 행을 잠그지 않는다")
    void exploringStatusTakesNoLock() throws Exception {
        seed();
        String body = mvc.perform(get("/community/profiles/{id}/mindmap", owner.getId()).with(as(viewer)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Number node = JsonPath.read(body, "$.data.nodes[0].nodeId");
        SqlCapture.clear();

        mvc.perform(get("/community/exploring").param("targetType", "NODE").param("targetId", node.toString())
                        .with(as(viewer)))
                .andExpect(okEnvelope());

        assertThat(SqlCapture.writesAndLocks()).isEmpty();
    }

    @Test
    @DisplayName("공유 세션 상세 · 커뮤니티 세션 상세도 읽기만 한다")
    void sessionDetailReads() throws Exception {
        seed();
        mvc.perform(post("/community/me/shared-sessions").with(as(owner)).with(json(Map.of("sessionId", prague.getId()))))
                .andExpect(status().is2xxSuccessful());
        entityManager.flush();
        entityManager.clear();
        SqlCapture.clear();

        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", owner.getId(), prague.getId()).with(as(viewer)))
                .andExpect(okEnvelope());
        mvc.perform(get("/community/sessions/{id}", prague.getId()).with(as(viewer))).andExpect(okEnvelope());

        assertThat(SqlCapture.writesAndLocks()).isEmpty();
    }

    @Test
    @DisplayName("프로필 행이 없는 회원을 조회해도 행을 만들지 않는다")
    void memberWithoutProfileRowIsNotCreated() throws Exception {
        seed();
        Member bare = member("bare");
        entityManager.flush();
        SqlCapture.clear();

        mvc.perform(get("/community/profiles/{id}", bare.getId()).with(as(viewer)));
        mvc.perform(get("/community/me/hashtags").with(as(bare)));

        assertThat(SqlCapture.writesAndLocks()).isEmpty();
        assertThat(communityProfiles.findById(bare.getId())).isEmpty();
    }
}
