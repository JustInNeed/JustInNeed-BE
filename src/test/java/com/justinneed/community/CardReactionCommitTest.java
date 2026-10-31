package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// 트랜잭션을 실제로 커밋해야 재현되는 동시 클릭. 실제 Postgres 재현은 HTTP 로 따로 돌린다
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:community_card_reaction;MODE=PostgreSQL;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
class CardReactionCommitTest {
    private static final int CLICKS = 6;

    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired BrowsingSessionRepository sessions;
    @Autowired CommunityCardSync cardSync;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;

    private Long member() {
        return members.saveAndFlush(new Member(SocialProvider.KAKAO, "reaction-" + UUID.randomUUID(), null, null)).getId();
    }
    private Long card(Long owner) {
        var session = new BrowsingSession(owner, "동시 클릭", LocalDateTime.now());
        session.complete(LocalDateTime.now(), 1);
        session.update(null, null, true, false, List.of("동시", "클릭"));
        session.replaceSources(List.of(new Source("글", "https://example.com/" + UUID.randomUUID(), "발췌")));
        BrowsingSession saved = sessions.saveAndFlush(session);
        cardSync.sync(saved);
        return saved.getId();
    }
    private int rows(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
    private List<Integer> together(List<Callable<Integer>> clicks) throws Exception {
        var pool = Executors.newFixedThreadPool(clicks.size());
        var ready = new CountDownLatch(clicks.size());
        var start = new CountDownLatch(1);
        try {
            var calls = new ArrayList<Future<Integer>>();
            for (var click : clicks) {
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    start.await(5, TimeUnit.SECONDS);
                    return click.call();
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var call : calls) statuses.add(call.get(20, TimeUnit.SECONDS));
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }
    private Callable<Integer> click(Long actor, Long card, String action, String field, boolean value) {
        return () -> mvc.perform(patch("/community/mindmaps/{id}/" + action, card)
                        .header("Authorization", "Bearer " + tokens.createAccessToken(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":" + value + "}"))
                .andReturn().getResponse().getStatus();
    }
    private List<Integer> clicks(Long actor, Long card, String action, String field, boolean value) throws Exception {
        var clicks = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < CLICKS; i++) clicks.add(click(actor, card, action, field, value));
        return together(clicks);
    }

    @Test
    void simultaneousLikesLeaveOneRow() throws Exception {
        Long actor = member();
        Long card = card(member());
        String count = "select count(*) from community_mindmap_likes where actor_id = ? and session_id = ?";
        for (int round = 0; round < 3; round++) {
            assertThat(clicks(actor, card, "like", "liked", true)).containsOnly(200);
            assertThat(rows(count, actor, card)).isEqualTo(1);
            assertThat(clicks(actor, card, "like", "liked", false)).containsOnly(200);
            assertThat(rows(count, actor, card)).isZero();
        }
    }

    @Test
    void simultaneousExploringLeavesOneRowAndOneActivityPerTag() throws Exception {
        Long actor = member();
        Long owner = member();
        Long card = card(owner);
        Long sibling = card(owner);
        String count = "select count(*) from community_mindmap_explorations where actor_id = ? and session_id = ?";
        String activity = "select count(*) from community_activities where actor_id = ? and kind = 'EXPLORE'";

        assertThat(clicks(actor, card, "exploring", "exploring", true)).containsOnly(200);
        assertThat(rows(count, actor, card)).isEqualTo(1);
        assertThat(rows(activity, actor)).isEqualTo(2);

        // 켜고 끄기가 섞여도, 같은 태그의 다른 카드를 함께 눌러도 500 이 없고 활동은 태그마다 하나다
        var mixed = new ArrayList<Callable<Integer>>();
        for (int i = 0; i < CLICKS; i++) {
            mixed.add(click(actor, card, "exploring", "exploring", i % 2 == 0));
            mixed.add(click(actor, sibling, "exploring", "exploring", true));
        }
        assertThat(together(mixed)).containsOnly(200);
        assertThat(rows(activity, actor)).isEqualTo(2);
    }

    @Test
    void simultaneousBookmarksLeaveOneRow() throws Exception {
        Long actor = member();
        Long card = card(member());
        String count = "select count(*) from community_bookmarks where user_id = ? and card_session_id = ?";
        for (int round = 0; round < 3; round++) {
            assertThat(clicks(actor, card, "bookmark", "bookmarked", true)).containsOnly(200);
            assertThat(rows(count, actor, card)).isEqualTo(1);
            assertThat(clicks(actor, card, "bookmark", "bookmarked", false)).containsOnly(200);
            assertThat(rows(count, actor, card)).isZero();
        }
        assertThat(rows("select count(*) from community_activities where actor_id = ? and kind = 'BOOKMARK'", actor))
                .isEqualTo(2);
    }
}
