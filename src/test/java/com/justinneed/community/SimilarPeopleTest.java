package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class SimilarPeopleTest extends CommunityTestSupport {
    private static final String SIMILAR = "/community/recommendations/profiles/similar";
    private static final String ALPHA = "유사알파";
    private static final String BETA = "유사베타";
    private static final String GAMMA = "유사감마";
    private static final String OTHER = "유사무관";

    @Autowired EntityManager entities;
    @Autowired JdbcTemplate jdbc;

    private Long person(String nickname) {
        return members.saveAndFlush(new Member(SocialProvider.KAKAO, "similar-" + UUID.randomUUID(), nickname, null)).getId();
    }
    private void interests(Long id, String... tags) {
        var profile = profiles.findById(id).orElseGet(() -> new CommunityProfile(id));
        profile.replaceInterests(List.of(tags));
        profiles.saveAndFlush(profile);
    }
    private void hidden(Long id, String... tags) {
        var profile = profiles.findById(id).orElseGet(() -> new CommunityProfile(id));
        profile.replaceHiddenHashtags(List.of(tags));
        profiles.saveAndFlush(profile);
    }
    private BrowsingSession sessionAt(Long owner, LocalDateTime endedAt, String... tags) {
        var row = session(owner, true, tags);
        row.complete(endedAt, 1);
        return sessions.saveAndFlush(row);
    }
    private ResultActions similar(Long viewer) throws Exception {
        return mvc.perform(get(SIMILAR).param("limit", "20").header("Authorization", auth(viewer)));
    }
    private static Object[] ints(Long... ids) {
        return Arrays.stream(ids).map(Long::intValue).toArray();
    }
    private void withdraw(Long id) {
        var member = members.findById(id).orElseThrow();
        member.withdraw("test", LocalDateTime.now());
        members.saveAndFlush(member);
    }

    @Test
    void ranksBySharedCountAcrossSessionsAndInterests() throws Exception {
        interests(viewerId, ALPHA, BETA, GAMMA);
        Long one = person("one");
        Long three = person("three");
        Long two = person("two");
        session(one, true, ALPHA, OTHER);
        session(three, true, ALPHA);
        session(three, true, BETA);
        interests(three, GAMMA);
        interests(two, BETA, GAMMA);

        similar(viewerId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(three, two, one))))
                .andExpect(jsonPath("$.data.people[0].nickname").value("three"))
                .andExpect(jsonPath("$.data.people[0].matchedHashtags", containsInAnyOrder(ALPHA, BETA, GAMMA)))
                .andExpect(jsonPath("$.data.people[0].following").value(false))
                .andExpect(jsonPath("$.data.people[2].matchedHashtags").value(contains(ALPHA)));
    }

    @Test
    void viewerSessionsCountOnTheViewerSide() throws Exception {
        session(viewerId, true, ALPHA);
        Long other = person("other");
        interests(other, ALPHA);

        similar(viewerId).andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(other))));
    }

    @Test
    void tiesGoToMostRecentPublicActivityThenLowerId() throws Exception {
        interests(viewerId, ALPHA);
        var now = LocalDateTime.of(2026, 10, 1, 12, 0);
        Long interestOnly = person("noact");
        Long older = person("older");
        Long newer = person("newer");
        Long sameFirst = person("same1");
        Long sameSecond = person("same2");
        interests(interestOnly, ALPHA);
        sessionAt(older, now.minusDays(3), ALPHA);
        sessionAt(newer, now.minusDays(3), ALPHA);
        sessionAt(newer, now, OTHER); // 겹치지 않는 공개 세션도 공개 활동이다
        sessionAt(sameFirst, now.minusDays(1), ALPHA);
        sessionAt(sameSecond, now.minusDays(1), ALPHA);

        similar(viewerId).andExpect(jsonPath("$.data.people[*].userId")
                .value(contains(ints(newer, sameFirst, sameSecond, older, interestOnly))));
    }

    @Test
    void privateActivityDoesNotBreakTies() throws Exception {
        interests(viewerId, ALPHA);
        var now = LocalDateTime.of(2026, 10, 1, 12, 0);
        Long first = person("first");
        Long second = person("second");
        sessionAt(first, now.minusDays(1), ALPHA);
        sessionAt(second, now.minusDays(2), ALPHA);
        var secret = sessionAt(second, now, OTHER);
        secret.update(null, null, false, false, null);
        sessions.saveAndFlush(secret);

        similar(viewerId).andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(first, second))));
    }

    @Test
    void excludesSelfWithdrawnAndPeopleWithoutOverlap() throws Exception {
        interests(viewerId, ALPHA);
        session(viewerId, true, ALPHA);
        Long gone = person("gone");
        session(gone, true, ALPHA);
        interests(gone, ALPHA);
        withdraw(gone);
        Long stranger = person("stranger");
        session(stranger, true, OTHER);
        Long match = person("match");
        interests(match, ALPHA);

        similar(viewerId).andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(match))));
    }

    @Test
    void onlyPublicCompletedLiveSessionsContributeTags() throws Exception {
        interests(viewerId, ALPHA);
        Long secret = person("secret");
        session(secret, false, ALPHA);
        Long deleted = person("deleted");
        var removed = session(deleted, true, ALPHA);
        removed.delete();
        sessions.saveAndFlush(removed);
        Long inProgress = person("running");
        var running = new BrowsingSession(inProgress, "running", LocalDateTime.now());
        running.update(null, null, true, false, List.of(ALPHA));
        running.replaceSources(List.of(new Source("t", "https://example.com", "e")));
        sessions.saveAndFlush(running);

        similar(viewerId).andExpect(jsonPath("$.data.people", hasSize(0)));
    }

    @Test
    void viewerPrivateSessionTagsDoNotMatch() throws Exception {
        session(viewerId, false, ALPHA);
        Long other = person("other");
        interests(other, ALPHA);

        similar(viewerId).andExpect(jsonPath("$.data.people", hasSize(0)));
    }

    @Test
    void matchesIgnoringCaseAndCountsATagOnce() throws Exception {
        interests(viewerId, "UniqTag", ALPHA);
        Long once = person("once");
        session(once, true, "uniqtag");
        session(once, true, "UNIQTAG");
        interests(once, "uniqtag");
        Long twice = person("twice");
        interests(twice, "uniqtag", ALPHA);

        similar(viewerId).andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(twice, once))))
                .andExpect(jsonPath("$.data.people[1].matchedHashtags").value(contains("uniqtag")));
    }

    @Test
    void hiddenTagsNeverReachMatchedHashtagsOrTheScore() throws Exception {
        interests(viewerId, ALPHA, BETA, GAMMA);
        Long hider = person("hider");
        session(hider, true, ALPHA, BETA); // 비공개 태그가 섞인 세션도 나머지 태그는 공개다
        interests(hider, GAMMA);
        hidden(hider, BETA, GAMMA);
        Long plain = person("plain");
        interests(plain, ALPHA, BETA);

        similar(viewerId)
                .andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(plain, hider))))
                .andExpect(jsonPath("$.data.people[1].matchedHashtags").value(contains(ALPHA)));
    }

    @Test
    void hiddenMatchingIgnoresCase() throws Exception {
        interests(viewerId, "cafetag", ALPHA);
        Long hider = person("hider");
        session(hider, true, "CafeTag", ALPHA);
        hidden(hider, "CAFETAG");

        similar(viewerId).andExpect(jsonPath("$.data.people[0].matchedHashtags").value(contains(ALPHA)));
    }

    @Test
    void personOverlappingOnlyOnHiddenTagIsNotRecommended() throws Exception {
        interests(viewerId, ALPHA);
        Long hider = person("hider");
        session(hider, true, ALPHA);
        hidden(hider, ALPHA);

        similar(viewerId).andExpect(jsonPath("$.data.people", hasSize(0)));
    }

    @Test
    void viewerHiddenTagsDoNotMatchEither() throws Exception {
        interests(viewerId, ALPHA);
        hidden(viewerId, ALPHA);
        Long other = person("other");
        interests(other, ALPHA);

        similar(viewerId).andExpect(jsonPath("$.data.people", hasSize(0)));
    }

    @Test
    void personWithoutNicknameStaysWithNullNickname() throws Exception {
        interests(viewerId, ALPHA);
        Long unnamed = person(null);
        interests(unnamed, ALPHA);
        Long sessionsOnly = person(null);
        session(sessionsOnly, true, ALPHA);

        similar(viewerId)
                .andExpect(jsonPath("$.data.people", hasSize(2)))
                .andExpect(jsonPath("$.data.people[0]", hasKey("nickname")))
                .andExpect(jsonPath("$.data.people[0].nickname").value(nullValue()))
                .andExpect(jsonPath("$.data.people[1].nickname").value(nullValue()));
    }

    @Test
    void followingStateIsPerViewer() throws Exception {
        interests(viewerId, ALPHA);
        Long followed = person("followed");
        Long notFollowed = person("skipped");
        interests(followed, ALPHA);
        interests(notFollowed, ALPHA);
        mvc.perform(patch("/community/profiles/{userId}/follow", followed).header("Authorization", auth(viewerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"following\":true}"))
                .andExpect(status().isOk());

        similar(viewerId)
                .andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(followed, notFollowed))))
                .andExpect(jsonPath("$.data.people[0].following").value(true))
                .andExpect(jsonPath("$.data.people[1].following").value(false));
        interests(ownerId, ALPHA);
        similar(ownerId).andExpect(jsonPath("$.data.people[?(@.userId==" + followed + ")].following").value(contains(false)));
    }

    @Test
    void limitDefaultsToThreeAndCapsAtTwenty() throws Exception {
        interests(viewerId, ALPHA);
        for (int i = 0; i < 22; i++) interests(person(null), ALPHA);

        mvc.perform(get(SIMILAR).header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.people", hasSize(3)));
        mvc.perform(get(SIMILAR).param("limit", "1").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.people", hasSize(1)));
        mvc.perform(get(SIMILAR).param("limit", "20").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.people", hasSize(20)));
        mvc.perform(get(SIMILAR).param("limit", "21").header("Authorization", auth(viewerId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("limit 은 1~20 사이여야 합니다."));
        mvc.perform(get(SIMILAR).param("limit", "0").header("Authorization", auth(viewerId)))
                .andExpect(status().isBadRequest());
        mvc.perform(get(SIMILAR).param("limit", "abc").header("Authorization", auth(viewerId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void viewerWithoutTagsGetsAnEmptyList() throws Exception {
        Long other = person("other");
        interests(other, ALPHA);

        similar(viewerId).andExpect(status().isOk()).andExpect(jsonPath("$.data.people", hasSize(0)));
    }

    @Test
    void loginIsRequired() throws Exception {
        mvc.perform(get(SIMILAR)).andExpect(status().isUnauthorized());
    }

    @Test
    void readsProjectionsOnlyAndWritesNothing() throws Exception {
        session(viewerId, true, ALPHA);
        session(ownerId, true, ALPHA);
        entities.flush();
        entities.clear();
        Integer hashtagsBefore = jdbc.queryForObject("select count(*) from community_hashtags where owner_id in (?, ?)",
                Integer.class, viewerId, ownerId);

        similar(viewerId).andExpect(jsonPath("$.data.people[*].userId").value(contains(ints(ownerId))));

        // 세션 · 프로필 · 회원 엔티티를 하나도 영속화하지 않는다 (sources · 요약을 읽지 않는다)
        assertThat(entities.unwrap(Session.class).getStatistics().getEntityCount()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from community_profiles where user_id in (?, ?)",
                Integer.class, viewerId, ownerId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from community_hashtags where owner_id in (?, ?)",
                Integer.class, viewerId, ownerId)).isEqualTo(hashtagsBefore);
    }
}
