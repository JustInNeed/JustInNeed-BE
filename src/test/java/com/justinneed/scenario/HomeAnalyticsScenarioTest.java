package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 홈 대시보드 (/home) · 분석/추천 (/analytics) — 아직 API 가 없어 화면 전체가 목 데이터다 (D표 '홈 · 분석 집계 API' 반영 결정).
 * FE 목: HOME_TOP_TAGS · HOME_WEEK_ACTIVITY · HOME_TIMELINE · HOME_WEEK_SESSIONS · WEEKLY_SESSIONS · CATEGORY_RATIO ·
 * READING_BEHAVIOR · EXPLORATION_STATS, 이번 주 타임라인 규칙은 기능 트래킹표 '디자인 결정 상세' 참고.
 * 아래 경로 · 필드명은 모두 제안이고, 구현되면 실제 이름에 맞춰 고친 뒤 @Disabled 를 지운다.
 *
 * <p>시드 (지금 기준 상대 시각): 이번 주 세션 3개(30 · 40 · 50분) + 10일 전 세션 1개(20분).
 * 자정 직후 40분 안에 돌리면 '오늘' 세션이 어제로 넘어가니, 구현할 때 Clock 을 주입해 고정하는 것을 권한다.
 */
@DisplayName("홈 · 분석 화면 (API 미구현 — 기대 계약)")
class HomeAnalyticsScenarioTest extends ScenarioTestSupport {

    private static final String REASON = "API 미구현: 홈 · 분석 집계 (D표 반영 결정) — 경로 · 필드명은 제안, 구현되면 켠다";

    private Member me;
    private BrowsingSession today;
    private BrowsingSession yesterday;
    private BrowsingSession threeDaysAgo;

    @BeforeEach
    void seed() {
        me = member("sprout");
        LocalDateTime now = LocalDateTime.now().withNano(0);
        today = session(me, "스프링 JPA 연관관계 정리", now.minusMinutes(40), now.minusMinutes(10), 3, false, false,
                List.of("자바", "스프링", "JPA"),
                List.of(new Source("Spring Data JPA", "https://docs.spring.io/spring-data/jpa/reference/", "리포지토리 메서드"),
                        new Source("Baeldung JPA", "https://www.baeldung.com/jpa-entities", "엔티티 매핑"),
                        new Source("Stack Overflow", "https://stackoverflow.com/questions/1", "N+1 문제")),
                "# 스프링 JPA");
        yesterday = session(me, "JPA 페치 조인", now.minusDays(1).minusMinutes(50), now.minusDays(1).minusMinutes(10), 2,
                false, false, List.of("JPA", "스프링"),
                List.of(new Source("velog", "https://velog.io/@dev/fetch-join", "페치 조인 정리"),
                        new Source("Stack Overflow", "https://stackoverflow.com/questions/2", "distinct")),
                "# 페치 조인");
        threeDaysAgo = session(me, "연관관계 매핑 뉴스", now.minusDays(3).minusMinutes(60), now.minusDays(3).minusMinutes(10), 2,
                false, false, List.of("JPA", "연관관계"),
                List.of(new Source("네이버 뉴스", "https://news.naver.com/article/1", "기사"),
                        new Source("기타 사이트", "https://unknown-site.example/post", "메모")),
                "# 연관관계");
        session(me, "파리 항공권 비교", now.minusDays(10).minusMinutes(30), now.minusDays(10).minusMinutes(10), 1,
                false, false, List.of("파리", "항공권"),
                List.of(new Source("스카이스캐너", "https://www.skyscanner.co.kr/", "가격")), "# 항공권");
    }

    @Test
    @Disabled(REASON + " (GET /home/summary)")
    @DisplayName("홈 통계 카드: 누적 노드(해시태그 수) · 이번 주 세션 수 · 평균 세션 길이 · Deep Reading 비율이 온다")
    void homeSummary() throws Exception {
        mvc.perform(get("/home/summary").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.totalHashtagCount").value(6))
                .andExpect(jsonPath("$.data.weeklySessionCount").value(3))
                .andExpect(jsonPath("$.data.averageSessionMinutes").value(40))
                .andExpect(jsonPath("$.data.deepReadingRatio").isNumber())
                .andExpect(jsonPath("$.data.deepReadingRatioDiff").isNumber());
    }

    @Test
    @Disabled(REASON + " (GET /home/weekly-activity)")
    @DisplayName("이번 주 활동: 최근 7일 요일별 탐색 시간(분)이 7칸으로 오고 합계는 이번 주 세션 시간과 같다")
    void weeklyActivity() throws Exception {
        var result = mvc.perform(get("/home/weekly-activity").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.days", hasSize(7)))
                .andExpect(jsonPath("$.data.days[0].day").isString());
        List<Integer> minutes = read(result, "$.data.days[*].minutes");
        assertThat(minutes.stream().mapToInt(Integer::intValue).sum()).isEqualTo(30 + 40 + 50);
    }

    @Test
    @Disabled(REASON + " (GET /home/top-hashtags?limit=10)")
    @DisplayName("상위 해시태그: 등장한 세션 수가 많은 순으로 최대 10개가 개수와 함께 온다")
    void topHashtags() throws Exception {
        mvc.perform(get("/home/top-hashtags").param("limit", "10").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data", hasSize(6)))
                .andExpect(jsonPath("$.data[0].hashtag").value("JPA"))
                .andExpect(jsonPath("$.data[0].count").value(3))
                .andExpect(jsonPath("$.data[1].hashtag").value("스프링"))
                .andExpect(jsonPath("$.data[1].count").value(2));
    }

    @Test
    @Disabled(REASON + " (GET /home/timeline?range=today)")
    @DisplayName("오늘의 탐색 타임라인(ANA-2-1 · 2-2): 시간대마다 수집 해시태그와 방문 사이트(도메인)가 온다")
    void todayTimeline() throws Exception {
        mvc.perform(get("/home/timeline").param("range", "today").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.slots", hasSize(1)))
                .andExpect(jsonPath("$.data.slots[0].startTime").isString())
                .andExpect(jsonPath("$.data.slots[0].endTime").isString())
                .andExpect(jsonPath("$.data.slots[0].hashtags").value(contains("자바", "스프링", "JPA")))
                .andExpect(jsonPath("$.data.slots[0].sites[*].domain").value(containsInAnyOrder(
                        "docs.spring.io", "baeldung.com", "stackoverflow.com")));
    }

    @Test
    @Disabled(REASON + " (GET /home/timeline?range=week)")
    @DisplayName("이번 주 탐색 타임라인: 오늘 포함 7일이 오래된 날부터 오고, 날마다 대표 해시태그 3개 · 사이트 3개, 기록 없는 날은 빈 목록")
    void weekTimeline() throws Exception {
        mvc.perform(get("/home/timeline").param("range", "week").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.days", hasSize(7)))
                .andExpect(jsonPath("$.data.days[6].isToday").value(true))
                .andExpect(jsonPath("$.data.days[6].hashtags").value(contains("자바", "스프링", "JPA")))
                .andExpect(jsonPath("$.data.days[5].hashtags").value(contains("JPA", "스프링")))
                .andExpect(jsonPath("$.data.days[5].sites[*].domain").value(containsInAnyOrder("velog.io", "stackoverflow.com")))
                .andExpect(jsonPath("$.data.days[4].hashtags", hasSize(0)))
                .andExpect(jsonPath("$.data.days[3].hashtags").value(contains("JPA", "연관관계")))
                .andExpect(jsonPath("$.data.days[*].hashtags[*]").value(not(hasItem("파리"))));
    }

    @Test
    @Disabled(REASON + " (GET /home/mindmap?period=today|week|month|all)")
    @DisplayName("홈 전체 마인드맵: 기간 필터(today · week · month · all)로 그 기간에 본 노드만 온다")
    void homeMindmapByPeriod() throws Exception {
        mvc.perform(get("/home/mindmap").param("period", "today").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.hashtags[*].name").value(containsInAnyOrder("자바", "스프링", "JPA")))
                .andExpect(jsonPath("$.data.nodes", hasSize(3)));
        mvc.perform(get("/home/mindmap").param("period", "all").with(as(me)))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(hasItem("파리")))
                .andExpect(jsonPath("$.data.nodes", hasSize(8)));
    }

    @Test
    @Disabled(REASON + " (GET /analytics/weekly-insight)")
    @DisplayName("분석 롤링 배너 '이번 주 인사이트': 이번 주 세션 중 하나의 제목 · 요약이 온다")
    void weeklyInsight() throws Exception {
        mvc.perform(get("/analytics/weekly-insight").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessionId").value(anyOf(
                        is(today.getId().intValue()), is(yesterday.getId().intValue()),
                        is(threeDaysAgo.getId().intValue()))))
                .andExpect(jsonPath("$.data.title").isString())
                .andExpect(jsonPath("$.data.summary").isString());
    }

    @Test
    @Disabled(REASON + " (GET /analytics/categories)")
    @DisplayName("카테고리별 방문 비율(ANA-1-1): 블로그 · 공식문서 · 커뮤니티 · 뉴스 · 기타 비율과 개수가 오고 비율 합은 100")
    void categoryRatio() throws Exception {
        var result = mvc.perform(get("/analytics/categories").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[*].name").value(containsInAnyOrder("블로그", "공식문서", "커뮤니티", "뉴스", "기타")))
                .andExpect(jsonPath("$.data[?(@.name == '블로그')].count").value(contains(2)))
                .andExpect(jsonPath("$.data[?(@.name == '커뮤니티')].count").value(contains(2)))
                .andExpect(jsonPath("$.data[?(@.name == '공식문서')].count").value(contains(1)));
        List<Number> ratios = read(result, "$.data[*].ratio");
        assertThat(ratios.stream().mapToDouble(Number::doubleValue).sum()).isCloseTo(100.0, within(1.0));
    }

    @Test
    @Disabled(REASON + " (GET /analytics/exploration-stats)")
    @DisplayName("탐색 성향 통계(ANA-4 · 2-3): 세션 평균 URL 수 · 평균 페이지 체류 · 평균 세션 길이가 온다")
    void explorationStats() throws Exception {
        var result = mvc.perform(get("/analytics/exploration-stats").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.averageDwellSeconds").isNumber())
                .andExpect(jsonPath("$.data.averageSessionMinutes").value(40));
        Number urls = read(result, "$.data.averageUrlCount");
        assertThat(urls.doubleValue()).isCloseTo(7 / 3.0, within(0.1));
    }

    @Test
    @Disabled(REASON + " (GET /analytics/reading-behavior)")
    @DisplayName("읽기 행동 분포(ANA-3-1): Idle · Scanning · Reading · Deep Reading 4분류 비율이 오고 합은 100")
    void readingBehavior() throws Exception {
        var result = mvc.perform(get("/analytics/reading-behavior").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data[*].label").value(contains("Idle", "Scanning", "Reading", "Deep Reading")));
        List<Number> pct = read(result, "$.data[*].pct");
        assertThat(pct.stream().mapToDouble(Number::doubleValue).sum()).isCloseTo(100.0, within(1.0));
    }

    @Test
    @Disabled(REASON + " (GET /analytics/heatmap)")
    @DisplayName("시간대별 히트맵(ANA-2-4 · 2-5): 요일 7 × 시간 24 칸에 웹서핑 빈도와 집중 시간이 온다")
    void heatmap() throws Exception {
        mvc.perform(get("/analytics/heatmap").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.cells", hasSize(7 * 24)))
                .andExpect(jsonPath("$.data.cells[0].visits").isNumber())
                .andExpect(jsonPath("$.data.cells[0].focusMinutes").isNumber());
    }
}
