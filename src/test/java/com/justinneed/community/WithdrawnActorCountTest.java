package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.community.activity.ActivityKind;
import com.justinneed.community.activity.CommunityActivity;
import com.justinneed.community.activity.CommunityActivityRepository;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.community.reaction.ExplorationRepository;
import com.justinneed.community.reaction.InteractionService;
import com.justinneed.community.reaction.ProfileLikeRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 탈퇴 회원의 행동은 행을 지우지 않고 읽을 때 숫자에서만 뺀다. */
class WithdrawnActorCountTest extends CommunityTestSupport {
    @Autowired InteractionService interactions;
    @Autowired PublicGraphService graphs;
    @Autowired ProfileLikeRepository likes;
    @Autowired ExplorationRepository explorations;
    @Autowired CommunityActivityRepository activities;
    @Autowired EntityManager entityManager;

    private Long leaverId;

    @BeforeEach
    void createLeaver() {
        leaverId = members.saveAndFlush(new Member(SocialProvider.KAKAO, "community-leaver", "Leaver", null)).getId();
    }

    private void withdrawLeaver() {
        members.findById(leaverId).orElseThrow().withdraw(null, LocalDateTime.now());
        entityManager.flush();
    }

    @Test
    void profileLikeCountExcludesWithdrawnActorsButKeepsRows() throws Exception {
        interactions.like(viewerId, ownerId, true);
        interactions.like(leaverId, ownerId, true);
        assertThat(graphs.profile(ownerId, viewerId).likeCount()).isEqualTo(2);

        withdrawLeaver();

        assertThat(graphs.profile(ownerId, viewerId).likeCount()).isEqualTo(1);
        mvc.perform(get("/community/profiles/{id}", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.likeCount").value(1))
                .andExpect(jsonPath("$.data.likedByMe").value(true));
        // PATCH 응답 숫자도 같은 기준
        assertThat(interactions.like(viewerId, ownerId, true).likeCount()).isEqualTo(1);
        assertThat(interactions.like(viewerId, ownerId, false).likeCount()).isZero();
        assertThat(likes.count()).isEqualTo(1);
    }

    @Test
    void exploringCountExcludesWithdrawnActorsButKeepsRows() {
        session(ownerId, true, "Java");
        var node = graphs.graph(ownerId, null).nodes().get(0).nodeId();
        interactions.exploring(viewerId, TargetType.NODE, node, true);
        interactions.exploring(leaverId, TargetType.NODE, node, true);
        assertThat(interactions.status(viewerId, TargetType.NODE, node).exploringCount()).isEqualTo(2);

        withdrawLeaver();

        var status = interactions.status(viewerId, TargetType.NODE, node);
        assertThat(status.exploringCount()).isEqualTo(1);
        assertThat(status.exploring()).isTrue();
        assertThat(interactions.exploring(viewerId, TargetType.NODE, node, true).exploringCount()).isEqualTo(1);
        assertThat(interactions.exploring(viewerId, TargetType.NODE, node, false).exploringCount()).isZero();
        assertThat(explorations.count()).isEqualTo(1);
    }

    @Test
    void activityQueryExcludesWithdrawnActorsButKeepsRows() {
        Instant at = Instant.parse("2026-10-30T01:00:00Z");
        var day = LocalDate.of(2026, 10, 30);
        activities.save(new CommunityActivity(viewerId, TargetType.NODE, 1L, 1L, ActivityKind.EXPLORE, at, day));
        activities.save(new CommunityActivity(leaverId, TargetType.NODE, 1L, 1L, ActivityKind.EXPLORE, at, day));
        withdrawLeaver();

        var found = activities.findByActiveActorsBetween(at, at);
        assertThat(found).extracting(CommunityActivity::getActorId).containsExactly(viewerId);
        // 경계 밖은 나오지 않는다
        assertThat(activities.findByActiveActorsBetween(at.plus(1, ChronoUnit.SECONDS), at.plus(1, ChronoUnit.HOURS))).isEmpty();
        assertThat(activities.count()).isEqualTo(2);
    }
}
