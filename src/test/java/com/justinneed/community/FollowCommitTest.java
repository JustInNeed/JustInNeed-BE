package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

// 트랜잭션을 실제로 커밋해야 재현되는 동시 요청 · 행 잠금 규칙
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:community_follow_committed;MODE=PostgreSQL;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc
class FollowCommitTest {
    @Autowired MockMvc mvc;
    @Autowired MemberRepository members;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;

    private Long member() {
        return members.saveAndFlush(new Member(SocialProvider.KAKAO, "follow-" + UUID.randomUUID(), null, null)).getId();
    }
    private int rows(Long actor, Long target) {
        return jdbc.queryForObject("select count(*) from community_follows where actor_id = ? and target_id = ?",
                Integer.class, actor, target);
    }
    private List<Integer> clickTogether(Long actor, Long target, boolean following, int clicks) throws Exception {
        var pool = Executors.newFixedThreadPool(clicks);
        var ready = new CountDownLatch(clicks);
        var start = new CountDownLatch(1);
        try {
            var calls = new ArrayList<java.util.concurrent.Future<Integer>>();
            Callable<Integer> click = () -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return mvc.perform(patch("/community/profiles/{userId}/follow", target)
                                .header("Authorization", "Bearer " + tokens.createAccessToken(actor))
                                .contentType(MediaType.APPLICATION_JSON).content("{\"following\":" + following + "}"))
                        .andReturn().getResponse().getStatus();
            };
            for (int i = 0; i < clicks; i++) calls.add(pool.submit(click));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var statuses = new ArrayList<Integer>();
            for (var call : calls) statuses.add(call.get(20, TimeUnit.SECONDS));
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void simultaneousClicksAllSucceedAndLeaveOneRow() throws Exception {
        Long actor = member();
        Long target = member();

        for (int round = 0; round < 5; round++) {
            assertThat(clickTogether(actor, target, true, 6)).containsOnly(200);
            assertThat(rows(actor, target)).isEqualTo(1);
            assertThat(clickTogether(actor, target, false, 6)).containsOnly(200);
            assertThat(rows(actor, target)).isZero();
        }
    }
}
