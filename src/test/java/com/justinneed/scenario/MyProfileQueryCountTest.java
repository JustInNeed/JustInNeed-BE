package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** 마이프로필 조회의 SQL 수가 세션 수와 상관없이 같은지 센다. Hibernate 통계 설정 · DB 는 HomeQueryCountTest 와 같다. */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:query-count;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@DisplayName("마이프로필 조회 쿼리 수")
class MyProfileQueryCountTest extends ScenarioTestSupport {

    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private SessionResultRepository results;

    @ParameterizedTest
    @ValueSource(strings = {"/community/me/profile", "/community/me/hashtags", "/community/me/sessions",
            "/community/me/mindmaps", "/community/me/mindmap", "/community/me/mindmaps/{id}/sessions"})
    @DisplayName("세션이 1개든 30개든 SQL 수가 같다")
    void queryCountDoesNotGrowWithSessions(String path) throws Exception {
        Member me = member("sprout");
        BrowsingSession first = seedAnalyzedSessions(me, 0, 1);
        mvc.perform(post("/community/me/pinned-sessions").with(as(me)).with(json(Map.of("sessionId", first.getId()))))
                .andExpect(status().isCreated());
        String url = path.replace("{id}", first.getId().toString());
        long one = statementsFor(me, url);

        seedAnalyzedSessions(me, 1, 30);
        long thirty = statementsFor(me, url);

        assertThat(thirty).isEqualTo(one);
    }

    private long statementsFor(Member member, String url) throws Exception {
        // 저장하면서 영속성 컨텍스트에 올라간 세션을 비워야 요청이 실제로 DB 에서 다시 읽는다
        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get(url).with(as(member))).andExpect(okEnvelope());
        return statistics.getPrepareStatementCount();
    }

    /** 요약 · 분석 결과까지 있는 공개 완료 세션 [from, to) 번째. 첫 세션을 돌려준다. */
    private BrowsingSession seedAnalyzedSessions(Member owner, int from, int to) {
        LocalDateTime base = LocalDateTime.parse("2026-04-01T10:00:00");
        BrowsingSession first = null;
        for (int i = from; i < to; i++) {
            BrowsingSession session = session(owner, "세션 " + i, base.plusDays(i), base.plusDays(i).plusMinutes(30), 3,
                    false, true, List.of("로마"), List.of(), "# 세션 " + i);
            SessionResult result = new SessionResult(session.getId(), owner.getId());
            result.replace(Map.of("nodes", List.of(Map.of("id", "root:1", "type", "root", "keyword", "로마 " + i)),
                    "edges", List.of()), List.of(), List.of(), Map.of(), List.of(), List.of(), "user", "A");
            results.saveAndFlush(result);
            // 태그 목록(CommunityTags.owned)은 완료되지 않은 세션도 읽는다
            sessionWithStatus(owner, "실패 " + i, base.plusDays(i).toString(), null, SessionStatus.FAILED, List.of("로마"));
            first = first == null ? session : first;
        }
        return first;
    }
}
