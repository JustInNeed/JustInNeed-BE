package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.taggroup.domain.TagGroup;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 세션 목록 · 태그 그룹 조회의 SQL 수가 세션 수와 상관없이 같은지 (N+1 이 없는지) 센다.
 * BrowsingSession.summary 는 mappedBy 쪽 1:1 이라 LAZY 가 먹지 않아, fetch join 이 없으면 세션마다 요약을 따로 조회한다.
 * Hibernate 통계는 이 클래스에서만 켠다 (DB 도 따로 써서 다른 시나리오 테스트 컨텍스트와 섞이지 않는다).
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:query-count;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=WARN"
})
@DisplayName("세션 목록 조회 쿼리 수")
class SessionListQueryCountTest extends ScenarioTestSupport {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TagGroupRepository tagGroups;

    private Statistics statistics;

    @BeforeEach
    void setUpStatistics() {
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    @Test
    @DisplayName("세션 목록: 세션이 1개든 10개든 SQL 수가 같다 — 토큰 확인 1 + 목록 1 + 전체 · 즐겨찾기 개수 2")
    void listQueryCountDoesNotGrowWithSessions() throws Exception {
        Member me = member("sprout");
        seedSessions(me, 0, 1);
        long one = statementsFor(get("/sessions").with(as(me)), jsonPath("$.data.sessions.length()").value(1));

        seedSessions(me, 1, 10);
        long ten = statementsFor(get("/sessions").with(as(me)), jsonPath("$.data.sessions.length()").value(10));
        long favoritesOnly = statementsFor(get("/sessions").param("favorite", "true").with(as(me)),
                jsonPath("$.data.sessions.length()").value(5));

        assertThat(ten).isEqualTo(one);
        assertThat(favoritesOnly).isEqualTo(one);
        assertThat(one).isLessThanOrEqualTo(4);
    }

    @Test
    @DisplayName("태그 그룹: 그룹에 묶인 세션이 1개든 10개든 조회 · 수정 응답의 SQL 수가 같다")
    void tagGroupQueryCountDoesNotGrowWithSessions() throws Exception {
        Member me = member("sprout");
        TagGroup group = tagGroups.saveAndFlush(new TagGroup(me.getId(), "로마", List.of("로마"), 0));
        seedSessions(me, 0, 1);
        long getOne = statementsFor(get("/tag-groups").with(as(me)),
                jsonPath("$.data[0].sessions.length()").value(1));
        long patchOne = statementsFor(patch("/tag-groups/{id}", group.getId()).with(as(me))
                        .with(json(Map.of("hashtags", List.of("로마")))),
                jsonPath("$.data.sessions.length()").value(1));

        seedSessions(me, 1, 10);
        long getTen = statementsFor(get("/tag-groups").with(as(me)),
                jsonPath("$.data[0].sessions.length()").value(10));
        long patchTen = statementsFor(patch("/tag-groups/{id}", group.getId()).with(as(me))
                        .with(json(Map.of("hashtags", List.of("로마")))),
                jsonPath("$.data.sessions.length()").value(10));

        assertThat(getTen).isEqualTo(getOne);
        assertThat(patchTen).isEqualTo(patchOne);
        // 토큰 확인 1 + 그룹 1 + 세션 1
        assertThat(getOne).isLessThanOrEqualTo(3);
    }

    /** 요청 하나가 실행한 JDBC 문 수. */
    private long statementsFor(MockHttpServletRequestBuilder request, ResultMatcher expectation) throws Exception {
        // 저장하면서 영속성 컨텍스트에 올라간 세션을 비워야 요청이 실제로 DB 에서 다시 읽는다
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        mvc.perform(request)
                .andExpect(okEnvelope())
                .andExpect(expectation);
        return statistics.getPrepareStatementCount();
    }

    /** "로마" 태그 · 요약 본문이 있는 완료 세션 [from, to) 번째. 짝수 번째는 즐겨찾기. */
    private void seedSessions(Member owner, int from, int to) {
        LocalDateTime base = LocalDateTime.parse("2026-04-01T10:00:00");
        for (int i = from; i < to; i++) {
            session(owner, "세션 " + i, base.plusDays(i), base.plusDays(i).plusMinutes(30), 3, i % 2 == 0, false,
                    List.of("로마"), List.of(), "# 세션 " + i + "\n\n본문");
        }
    }
}
