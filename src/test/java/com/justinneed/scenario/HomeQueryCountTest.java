package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.summary.domain.Summary;
import com.justinneed.session.taggroup.domain.TagGroup;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * 홈 조회의 SQL 수가 세션 수와 상관없이 같은지 센다. 집계가 세션 전체를 훑으므로 N+1 이 나면 세션 수만큼 늘어난다.
 * unit_features 처럼 무거운 컬럼을 읽지 않도록 엔티티가 아니라 필요한 컬럼만 프로젝션으로 읽는다.
 * Hibernate 통계 설정 · DB 는 SessionListQueryCountTest 와 같다.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:query-count;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@DisplayName("홈 조회 쿼리 수")
class HomeQueryCountTest extends ScenarioTestSupport {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private SessionResultRepository results;

    @Autowired
    private TagGroupRepository tagGroups;

    @Test
    @DisplayName("세션이 1개든 30개든 SQL 수가 같다 — 토큰 확인 1 + 탈퇴 확인 1 + 세션 · 분석 결과 1 + 태그 그룹 1")
    void queryCountDoesNotGrowWithSessions() throws Exception {
        Member me = member("sprout");
        tagGroups.saveAndFlush(new TagGroup(me.getId(), "로마", List.of("로마"), 0));
        seedAnalyzedSessions(me, 0, 1);
        long one = statementsFor(me, 1);

        seedAnalyzedSessions(me, 1, 30);
        long thirty = statementsFor(me, 30);

        assertThat(thirty).isEqualTo(one);
        assertThat(one).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("세션 · 요약 · 분석 결과 엔티티를 읽지 않는다 (unit_features · selected_units · analysis_units · 요약을 조회하지 않음)")
    void doesNotLoadHeavyEntities() throws Exception {
        Member me = member("sprout");
        seedAnalyzedSessions(me, 0, 3);

        Statistics statistics = statistics();
        statementsFor(me, 3);

        assertThat(statistics.getEntityStatistics(BrowsingSession.class.getName()).getLoadCount()).isZero();
        assertThat(statistics.getEntityStatistics(SessionResult.class.getName()).getLoadCount()).isZero();
        assertThat(statistics.getEntityStatistics(Summary.class.getName()).getLoadCount()).isZero();
    }

    private Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** 요청 하나가 실행한 JDBC 문 수. */
    private long statementsFor(Member member, int sessionCount) throws Exception {
        // 저장하면서 영속성 컨텍스트에 올라간 세션을 비워야 요청이 실제로 DB 에서 다시 읽는다
        entityManager.flush();
        entityManager.clear();
        statistics().clear();
        mvc.perform(get("/home").with(as(member)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions.length()").value(sessionCount));
        return statistics().getPrepareStatementCount();
    }

    /** 요약 · 분석 결과까지 있는 완료 세션 [from, to) 번째. */
    private void seedAnalyzedSessions(Member owner, int from, int to) {
        LocalDateTime base = LocalDateTime.parse("2026-04-01T10:00:00");
        for (int i = from; i < to; i++) {
            BrowsingSession session = session(owner, "세션 " + i, base.plusDays(i), base.plusDays(i).plusMinutes(30), 3,
                    false, false, List.of("로마"), List.of(), "# 세션 " + i);
            SessionResult result = new SessionResult(session.getId(), owner.getId());
            result.replace(
                    Map.of("nodes", List.of(Map.of("id", "root:1", "type", "root", "keyword", "로마 " + i)),
                            "edges", List.of()),
                    List.of(Map.of("unitKey", "https://a#p1")),
                    List.of(Map.of("unitKey", "https://a#p1")),
                    Map.of("Deep Reading", Map.of("units", 1, "dwellMs", 1000)),
                    List.of(Map.of("url", "https://example.com/", "dwellMs", 200_000)),
                    List.of(Map.of("unitKey", "https://a#p1")),
                    "user",
                    "A");
            results.saveAndFlush(result);
        }
    }
}
