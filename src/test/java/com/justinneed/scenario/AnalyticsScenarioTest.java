package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 분석 / 추천 화면 (/analytics).
 *
 * <p>시각은 Clock 을 고정해 쓴다: KST 2026-10-31(토) 10:30. 최근 7일 = 10.25 ~ 10.31.
 * 분석 결과 JSON 은 AI 출력(pipeline/output.py) 모양 그대로 넣는다.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:analytics-scenario;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DisplayName("분석 / 추천 화면 (/analytics)")
class AnalyticsScenarioTest extends ScenarioTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-31T01:30:00Z");
    private static final String OFFICIAL_DOCS_TITLE = "공식 문서도 한 번 정독해보세요.";
    private static final String OFFICIAL_DOCS_BODY = "지금 공부하는 주제의 공식 문서나 원문을 한 번쯤 읽으며 핵심 개념을 정리해보는 건 어떨까요?";

    @MockitoBean
    private Clock clock;

    @Autowired
    private SessionResultRepository results;

    private Member me;

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(NOW);
        me = member("sprout");
    }

    private ResultActions card(String path) throws Exception {
        return mvc.perform(get("/analytics/" + path).with(as(me)));
    }

    @Nested
    @DisplayName("공통")
    class Common {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"exploration", "reading-behavior", "categories", "time-patterns"})
        @DisplayName("세션이 없으면 카드 데이터는 null 이다")
        void noDataIsNull(String path) throws Exception {
            card(path).andExpect(okEnvelope()).andExpect(jsonPath("$.data").value(nullValue()));
        }

        @Test
        @DisplayName("세션이 없으면 이번 주 세션 · 추천 행동은 빈 목록이다")
        void noDataIsEmptyList() throws Exception {
            card("week-sessions").andExpect(jsonPath("$.data.sessions", empty()));
            card("recommendations").andExpect(jsonPath("$.data.recommendations", empty()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"week-sessions", "exploration", "reading-behavior", "categories", "time-patterns",
                "recommendations"})
        @DisplayName("로그인하지 않으면 401 이다")
        void requiresLogin(String path) throws Exception {
            mvc.perform(get("/analytics/" + path)).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("다른 회원 · 삭제 · 진행 중 · 실패 세션은 어떤 카드에도 들어가지 않는다")
        void onlyMyCompletedSessions() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", "2026-10-31T09:30:00", 3),
                    summary(0, 0, 0, 1), List.of(visit("https://velog.io/a", 40_000, 1)),
                    List.of(unit("Deep Reading", 60_000, "2026-10-31T00:10:00+00:00")));

            Member other = member("wanderer");
            BrowsingSession others = session(other, "남의 세션", at("2026-10-31T09:00:00"), at("2026-10-31T09:30:00"), 1,
                    false, false, List.of(), List.of(), null);
            noise(others);
            BrowsingSession deleted = completed("2026-10-31T08:00:00", "2026-10-31T08:30:00", 1);
            noise(deleted);
            deleted.delete();
            sessions.saveAndFlush(deleted);
            for (SessionStatus status : List.of(SessionStatus.COLLECTING, SessionStatus.READY, SessionStatus.ANALYZING,
                    SessionStatus.FAILED)) {
                noise(sessionWithStatus(me, status.name(), "2026-10-31T07:00:00", "2026-10-31T07:30:00", status, List.of()));
            }

            card("week-sessions").andExpect(jsonPath("$.data.sessions", hasSize(1)));
            card("exploration").andExpect(jsonPath("$.data.avgUrlsPerSession").value(3))
                    .andExpect(jsonPath("$.data.avgSessionMinutes").value(30));
            card("reading-behavior").andExpect(jsonPath("$.data.shares.DEEP_READING").value(100));
            card("categories").andExpect(jsonPath("$.data.categories[0].name").value("블로그"))
                    .andExpect(jsonPath("$.data.categories[0].count").value(1));
            card("time-patterns").andExpect(jsonPath("$.data.focus[5][3]").value(1.0))
                    .andExpect(jsonPath("$.data.surfing[5][2]").value(0.0));
        }

        @Test
        @DisplayName("기간은 오늘 포함 최근 7일이고 세션 시작 시각(KST)으로 본다")
        void lastSevenDays() throws Exception {
            BrowsingSession first = completed("2026-10-25T00:00:00", "2026-10-25T00:10:00", 1);
            BrowsingSession last = completed("2026-10-31T23:59:59", null, 1);
            completed("2026-10-24T23:59:59", "2026-10-25T00:30:00", 1);
            completed("2026-11-01T00:00:00", null, 1);

            card("week-sessions")
                    .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(last, first))));
        }

        @Test
        @DisplayName("AI JSON 이 어긋나도 500 없이 읽을 수 있는 부분만 쓴다")
        void malformedJson() throws Exception {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("Idle", "많음");
            summary.put("Scanning", Map.of("dwellMs", 100));
            summary.put("Reading", Map.of("units", "3"));
            summary.put("Deep Reading", Map.of("units", 1));
            Map<String, Object> noUrl = new LinkedHashMap<>();
            noUrl.put("dwellMs", "많음");
            noUrl.put("visitCount", 1);
            Map<String, Object> negative = new LinkedHashMap<>(visit("https://velog.io/a", -5, -3));
            Map<String, Object> badTime = new LinkedHashMap<>(unit("Reading", 1000, "어제"));
            Map<String, Object> noTime = new LinkedHashMap<>();
            noTime.put("clusterLabel", "Idle");
            noTime.put("dwellMs", 1000);
            noTime.put("firstSeenAt", null);
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary, List.of(noUrl, negative),
                    Arrays.asList(badTime, noTime, unit("Scanning", "길게", "2026-10-31T00:10:00+00:00")));

            card("exploration").andExpect(okEnvelope());
            card("reading-behavior").andExpect(jsonPath("$.data.shares.DEEP_READING").value(100));
            card("categories").andExpect(okEnvelope());
            card("recommendations").andExpect(okEnvelope());
            card("time-patterns").andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("이번 주 세션")
    class WeekSessions {

        @Test
        @DisplayName("FE SessionListItem 모양으로 최신순으로 온다")
        void listItemShape() throws Exception {
            BrowsingSession older = session(me, "JPA 정리", at("2026-10-30T20:00:00"), at("2026-10-30T20:30:00"), 4,
                    true, true, List.of("자바", "JPA"), List.of(), null);
            BrowsingSession newer = completed("2026-10-31T09:00:00", null, 1);

            card("week-sessions")
                    .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(newer, older))))
                    .andExpect(content().json("""
                            {"data": {"sessions": [{}, {
                              "title": "JPA 정리", "startedAt": "2026-10-30T20:00:00", "endedAt": "2026-10-30T20:30:00",
                              "pageCount": 4, "status": "COMPLETED", "isFavorite": true, "isPublic": true,
                              "tags": ["자바", "JPA"]
                            }]}}
                            """));
        }
    }

    @Nested
    @DisplayName("탐색 성향 (ANA-4 · ANA-2-3)")
    class Exploration {

        @Test
        @DisplayName("세션 평균 URL · 평균 페이지 체류 · 평균 세션 길이를 세션별로 계산해 평균한다")
        void stats() throws Exception {
            // 페이지 3개 체류 180초 → 페이지 평균 60초
            analyzed(completed("2026-10-31T09:00:00", "2026-10-31T09:40:00", 3), dwell(0, 10_000, 20_000, 70_000),
                    List.of(visit("https://a.example/", 120_000, 2), visit("https://b.example/", 30_000, 1),
                            visit("https://c.example/", 30_000, 1)), List.of());
            // 페이지 2개 체류 40초 → 페이지 평균 20초
            analyzed(completed("2026-10-30T20:00:00", "2026-10-30T20:20:00", 12), dwell(0, 100, 0, 0),
                    List.of(visit("https://d.example/", 20_000, 1), visit("https://e.example/", 20_000, 1)), List.of());
            // pageVisits 없음 · 종료 시각 없음: URL 평균에만 들어간다
            analyzed(completed("2026-10-29T09:00:00", null, 2), summary(0, 0, 0, 2), null, List.of());

            card("exploration")
                    .andExpect(jsonPath("$.data.avgUrlsPerSession").value(6))
                    .andExpect(jsonPath("$.data.avgDwellSec").value(40))
                    .andExpect(jsonPath("$.data.avgSessionMinutes").value(30));
        }

        @ParameterizedTest(name = "URL {0}개 · 페이지 평균 {1}초 · 정독 {2}% → {3}")
        @CsvSource({
                "10, 29, 90, DISTRACTED",
                "10, 30, 90, FOCUSED",
                "10, 30, 59, BROAD",
                "9,  29, 60, FOCUSED",
                "9,  29, 59, QUICK_CHECK",
                "1,  60, 0,  QUICK_CHECK",
        })
        @DisplayName("성향 판정 기준의 경계")
        void judge(int urls, int dwellSec, int focusedPct, String expected) throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, urls), dwell(100 - focusedPct, 0, 0, focusedPct),
                    List.of(visit("https://a.example/", dwellSec * 1000, 1)), List.of());

            card("exploration").andExpect(jsonPath("$.data.type").value(expected));
        }

        @Test
        @DisplayName("7일 중 가장 많이 나온 성향의 엑셀 문구가 오고, 같은 수면 앞선 성향이다")
        void mostCommonType() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), dwell(0, 0, 0, 100),
                    List.of(visit("https://a.example/", 60_000, 1)), List.of());
            analyzed(completed("2026-10-30T09:00:00", null, 12), dwell(100, 0, 0, 0),
                    List.of(visit("https://a.example/", 1_000, 1)), List.of());

            card("exploration")
                    .andExpect(jsonPath("$.data.type").value("DISTRACTED"))
                    .andExpect(jsonPath("$.data.note.analysis").value("여러 페이지를 빠르게 오가며 탐색하는 경향이 있습니다."))
                    .andExpect(jsonPath("$.data.note.recommendation").value(
                            "지금 가장 중요한 문서 딱 하나만 남기고 나머지 탭은 잠시 닫아두세요. 15분만 타이머를 맞추고 하나의 글에만 온전히 몰입해 보는 걸 추천합니다."));

            analyzed(completed("2026-10-29T09:00:00", null, 1), dwell(0, 0, 0, 100),
                    List.of(visit("https://a.example/", 60_000, 1)), List.of());

            card("exploration")
                    .andExpect(jsonPath("$.data.type").value("FOCUSED"))
                    .andExpect(jsonPath("$.data.note.analysis").value("하나의 주제에 대해 깊이 있게 탐색하는 방식입니다."))
                    .andExpect(jsonPath("$.data.note.recommendation").value(nullValue()));
        }

        @Test
        @DisplayName("pageVisits 가 없는 세션뿐이면 통계만 오고 판정은 비어 있다")
        void cannotJudge() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", "2026-10-31T09:30:00", 4), summary(0, 0, 0, 2), null, List.of());

            card("exploration")
                    .andExpect(jsonPath("$.data.avgUrlsPerSession").value(4))
                    .andExpect(jsonPath("$.data.avgDwellSec").value(0))
                    .andExpect(jsonPath("$.data.avgSessionMinutes").value(30))
                    .andExpect(jsonPath("$.data.type").value(nullValue()))
                    .andExpect(jsonPath("$.data.note.analysis").value("아직 탐색 성향을 판정할 데이터가 부족해요."));
        }
    }

    @Nested
    @DisplayName("읽기 행동 분포 (ANA-3-1 · ANA-3-2)")
    class ReadingBehavior {

        @Test
        @DisplayName("유닛 수 기준이고 Idle 도 분모에 들어가며 비율 합은 100 이다")
        void unitBasedShares() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary(2, 5, 8, 7), null, List.of());

            card("reading-behavior")
                    .andExpect(jsonPath("$.data.shares.DEEP_READING").value(32))
                    .andExpect(jsonPath("$.data.shares.READING").value(36))
                    .andExpect(jsonPath("$.data.shares.SCANNING").value(23))
                    .andExpect(jsonPath("$.data.shares.IDLE").value(9));
        }

        @Test
        @DisplayName("체류시간이 아무리 길어도 비율은 유닛 수로만 정해진다")
        void ignoresDwell() throws Exception {
            Map<String, Object> summary = Map.of(
                    "Idle", Map.of("units", 9, "dwellMs", 0),
                    "Deep Reading", Map.of("units", 1, "dwellMs", 3_600_000));
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary, null, List.of());

            card("reading-behavior")
                    .andExpect(jsonPath("$.data.shares.DEEP_READING").value(10))
                    .andExpect(jsonPath("$.data.shares.IDLE").value(90));
        }

        @Test
        @DisplayName("여러 세션의 유닛 수를 합쳐서 계산한다 (세션 비율의 평균이 아님)")
        void sumsSessions() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary(0, 0, 0, 9), null, List.of());
            analyzed(completed("2026-10-30T09:00:00", null, 1), summary(1, 0, 0, 0), null, List.of());

            card("reading-behavior").andExpect(jsonPath("$.data.shares.DEEP_READING").value(90));
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "Deep Reading | 0 | 0 | 0 | 5 | 한 번 읽기 시작하면 깊이 있게 이해하는 편입니다. **Deep Reading 비중 100%**로 가장 높아요.",
                "Reading      | 0 | 0 | 5 | 0 | 글의 핵심을 차근차근 읽어 나가는 편입니다. **Reading 비중 100%**로 가장 높아요.",
                "Scanning     | 0 | 5 | 0 | 0 | 전체를 빠르게 훑으며 핵심을 찾는 경향이 있습니다. **Scanning 비중 100%**로 가장 높아요.",
                "Idle         | 5 | 0 | 0 | 0 | 정보를 찾기 위해 페이지 간 이동이 많은 탐색형 패턴입니다. **Idle 비중 100%**로 가장 높아요.",
                "동률이면 앞선 분류 | 5 | 5 | 5 | 5 | 한 번 읽기 시작하면 깊이 있게 이해하는 편입니다. **Deep Reading 비중 25%**로 가장 높아요.",
        })
        @DisplayName("가장 높은 분류의 문구가 온다")
        void note(String name, int idle, int scanning, int reading, int deep, String expected) throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary(idle, scanning, reading, deep), null, List.of());

            card("reading-behavior")
                    .andExpect(jsonPath("$.data.note.analysis").value(expected))
                    .andExpect(jsonPath("$.data.note.recommendation").value(nullValue()));
        }

        @Test
        @DisplayName("유닛이 하나도 없으면 null 이다")
        void noUnits() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary(0, 0, 0, 0), null, List.of());

            card("reading-behavior").andExpect(jsonPath("$.data").value(nullValue()));
        }

        @Test
        @DisplayName("같은 데이터면 홈 Deep Reading 비율과 같은 숫자다")
        void sameAsHome() throws Exception {
            // 33.5% 처럼 반올림과 합 100 맞추기가 어긋날 수 있는 값
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary(67, 166, 66, 201), null, List.of());

            Integer analytics = read(card("reading-behavior"), "$.data.shares.DEEP_READING");
            mvc.perform(get("/home").with(as(me)))
                    .andExpect(jsonPath("$.data.stats.deepReadingPct").value(analytics));
        }
    }

    @Nested
    @DisplayName("카테고리별 방문 비율 (ANA-1-1 · ANA-1-2)")
    class Categories {

        @Test
        @DisplayName("재방문을 포함해 세고, 많은 순으로 오며 비율 합은 100 이다")
        void countsRevisits() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 3), null, List.of(
                    visit("https://docs.spring.io/a", 1_000, 2),
                    visit("https://blog.naver.com/b", 1_000, 1),
                    visit("https://stackoverflow.com/c", 1_000, 1)), List.of());
            analyzed(completed("2026-10-30T09:00:00", null, 1), null, List.of(
                    visit("https://velog.io/d", 1_000, 2)), List.of());

            card("categories").andExpect(content().json("""
                    {"data": {"categories": [
                      {"name": "블로그", "pct": 50, "count": 3},
                      {"name": "공식문서", "pct": 33, "count": 2},
                      {"name": "커뮤니티", "pct": 17, "count": 1},
                      {"name": "뉴스", "pct": 0, "count": 0},
                      {"name": "기타", "pct": 0, "count": 0}
                    ]}}
                    """));
        }

        @Test
        @DisplayName("짧은 방문도 들어가고, 쿼리에 특수문자가 있어도 카테고리를 찾는다")
        void shortVisitAndOddQuery() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), null,
                    List.of(visit("https://blog.naver.com/x?a=1|2", 150, 1)), List.of());

            card("categories")
                    .andExpect(jsonPath("$.data.categories[0].name").value("블로그"))
                    .andExpect(jsonPath("$.data.categories[0].pct").value(100));
        }

        @Test
        @DisplayName("1위가 60% 이상이면 '중심' 문구, 아니면 '균형' 문구에 상위 3개가 나온다")
        void note() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), null, List.of(
                    visit("https://velog.io/a", 1_000, 6),
                    visit("https://docs.spring.io/b", 1_000, 4)), List.of());

            card("categories").andExpect(jsonPath("$.data.note.analysis")
                    .value("현재 탐색은 **블로그** 중심으로 이루어지고 있습니다. (전체 탐색의 60%)"));

            analyzed(completed("2026-10-30T09:00:00", null, 1), null, List.of(
                    visit("https://stackoverflow.com/c", 1_000, 5),
                    visit("https://news.naver.com/d", 1_000, 1)), List.of());

            card("categories").andExpect(jsonPath("$.data.note.analysis")
                    .value("다양한 유형의 출처를 균형 있게 활용하고 있습니다. (블로그 38% / 커뮤니티 31% / 공식문서 25%)"));
        }

        @Test
        @DisplayName("추천 행동이 있으면 첫 번째 추천을 추천 문구로 함께 준다")
        void recommendationInNote() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), null, List.of(
                    visit("https://velog.io/a", 1_000, 3), visit("https://tistory.com/b", 1_000, 3),
                    visit("https://docs.spring.io/c", 1_000, 2), visit("https://reuters.com/d", 1_000, 2)), List.of());

            card("categories").andExpect(jsonPath("$.data.note.recommendation")
                    .value(OFFICIAL_DOCS_TITLE + " " + OFFICIAL_DOCS_BODY));
        }

        @Test
        @DisplayName("pageVisits 가 없는 세션만 있으면 null 이다")
        void noPageVisits() throws Exception {
            analyzed(completed("2026-10-31T09:00:00", null, 1), summary(0, 0, 0, 1), null, List.of());

            card("categories").andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Nested
    @DisplayName("추천 행동 (ANA-1-4)")
    class Recommendations {

        private ResultActions titles(Map<String, Integer> visitsByUrl) throws Exception {
            List<Map<String, Object>> visits = new ArrayList<>();
            visitsByUrl.forEach((url, count) -> visits.add(visit(url, 1_000, count)));
            analyzed(completed("2026-10-31T09:00:00", null, 1), null, visits, List.of());
            return card("recommendations");
        }

        @Test
        @DisplayName("블로그 + 커뮤니티가 60% 이상이면 공식 문서를 권한다")
        void blogAndCommunity() throws Exception {
            titles(Map.of("https://velog.io/a", 2, "https://tistory.com/b", 2, "https://stackoverflow.com/c", 2,
                    "https://docs.spring.io/d", 2, "https://reuters.com/e", 2))
                    .andExpect(jsonPath("$.data.recommendations[*].title").value(contains(OFFICIAL_DOCS_TITLE)))
                    .andExpect(jsonPath("$.data.recommendations[0].body").value(OFFICIAL_DOCS_BODY));
        }

        @Test
        @DisplayName("블로그 + 커뮤니티가 59% 면 나오지 않는다")
        void blogAndCommunityBelow() throws Exception {
            titles(Map.of("https://velog.io/a", 20, "https://tistory.com/b", 20, "https://stackoverflow.com/c", 19,
                    "https://docs.spring.io/d", 21, "https://reuters.com/e", 20))
                    .andExpect(jsonPath("$.data.recommendations", empty()));
        }

        @Test
        @DisplayName("커뮤니티가 50% 이상이면 비율이 든 문구가 나오고, 49% 면 나오지 않는다")
        void community() throws Exception {
            titles(Map.of("https://stackoverflow.com/a", 25, "https://reddit.com/b", 25, "https://docs.spring.io/c", 50))
                    .andExpect(jsonPath("$.data.recommendations[*].title")
                            .value(contains("커뮤니티 탐색 비중이 50%로 가장 높아요.")));
        }

        @Test
        @DisplayName("커뮤니티 49% 면 커뮤니티 문구는 나오지 않는다")
        void communityBelow() throws Exception {
            titles(Map.of("https://stackoverflow.com/a", 24, "https://reddit.com/b", 25, "https://docs.spring.io/c", 51))
                    .andExpect(jsonPath("$.data.recommendations", empty()));
        }

        @Test
        @DisplayName("한 사이트가 60% 이상이면 그 사이트 이름이 든 문구가 나오고, www. 유무는 같은 사이트다")
        void oneSite() throws Exception {
            titles(Map.of("https://www.reuters.com/a", 30, "https://reuters.com/b", 30, "https://docs.spring.io/c", 40))
                    .andExpect(jsonPath("$.data.recommendations[*].title").value(contains("reuters.com에 정보 탐색이 몰려 있어요.")));
        }

        @Test
        @DisplayName("조건 여러 개가 맞으면 모두 온다")
        void several() throws Exception {
            titles(Map.of("https://stackoverflow.com/a", 7, "https://stackoverflow.com/b", 3))
                    .andExpect(jsonPath("$.data.recommendations", hasSize(3)));
        }
    }

    @Nested
    @DisplayName("시간대별 패턴 (ANA-2-4 · ANA-2-5)")
    class TimePatterns {

        private void units(Map<String, Object>... units) {
            analyzed(completed("2026-10-31T09:00:00", null, 1), null, null, List.of(units));
        }

        @Test
        @DisplayName("요일 7 × 3시간 구간 8 칸이고, 웹서핑은 가장 많은 칸 대비, 집중은 칸 안의 정독 비율이다")
        void grids() throws Exception {
            units(unit("Deep Reading", 60_000, "2026-10-31T00:30:00+00:00"), // 토 09:30 KST
                    unit("Scanning", 30_000, "2026-10-30T11:10:00+00:00"), // 금 20:10 KST
                    unit("Reading", 15_000, "2026-10-30T15:10:00+00:00"), // 토 00:10 KST
                    unit("Scanning", 15_000, "2026-10-30T15:20:00+00:00")); // 토 00:20 KST

            card("time-patterns")
                    .andExpect(jsonPath("$.data.surfing", hasSize(7)))
                    .andExpect(jsonPath("$.data.surfing[0]", hasSize(8)))
                    .andExpect(jsonPath("$.data.focus", hasSize(7)))
                    .andExpect(jsonPath("$.data.focus[0]", hasSize(8)))
                    .andExpect(jsonPath("$.data.surfing[5][3]").value(1.0))
                    .andExpect(jsonPath("$.data.surfing[4][6]").value(0.5))
                    .andExpect(jsonPath("$.data.surfing[5][0]").value(0.5))
                    .andExpect(jsonPath("$.data.focus[5][3]").value(1.0))
                    .andExpect(jsonPath("$.data.focus[4][6]").value(0.0))
                    .andExpect(jsonPath("$.data.focus[5][0]").value(0.5))
                    .andExpect(jsonPath("$.data.surfing[0][0]").value(0.0));
        }

        @Test
        @DisplayName("구간 경계는 KST 3시간 단위이고 월요일이 첫 줄, 일요일이 마지막 줄이다")
        void boundaries() throws Exception {
            units(unit("Reading", 1_000, "2026-10-25T17:59:59+00:00"), // 월 02:59 KST
                    unit("Reading", 1_000, "2026-10-25T18:00:00+00:00"), // 월 03:00 KST
                    unit("Reading", 1_000, "2026-10-25T14:59:59+00:00")); // 일 23:59 KST

            card("time-patterns")
                    .andExpect(jsonPath("$.data.surfing[0][0]").value(1.0))
                    .andExpect(jsonPath("$.data.surfing[0][1]").value(1.0))
                    .andExpect(jsonPath("$.data.surfing[6][7]").value(1.0));
        }

        @Test
        @DisplayName("본 적 없는 유닛(시각 null)과 체류 0 유닛은 칸에 들어가지 않는다")
        void skipsUnseen() throws Exception {
            Map<String, Object> unseen = new LinkedHashMap<>();
            unseen.put("clusterLabel", "Idle");
            unseen.put("dwellMs", 0);
            unseen.put("firstSeenAt", null);
            units(unseen, unit("Idle", 0, "2026-10-31T00:30:00+00:00"));

            card("time-patterns").andExpect(jsonPath("$.data").value(nullValue()));
        }

        @Test
        @DisplayName("체류가 가장 많은 구간이 30% 이상이면 '주로' 문구, 아니면 '하루 전반' 문구다")
        void peakNote() throws Exception {
            units(unit("Scanning", 30_000, "2026-10-31T00:30:00+00:00"), // 09시 구간
                    unit("Scanning", 40_000, "2026-10-30T15:10:00+00:00"), // 00시 구간
                    unit("Scanning", 30_000, "2026-10-30T11:10:00+00:00")); // 18시 구간

            card("time-patterns").andExpect(jsonPath("$.data.note.analysis").value(
                    "웹 탐색은 주로 **오전 0시 ~ 오전 3시**에 진행됩니다. 이 시간대에 가장 많은 정보 탐색과 읽기가 이루어지고 있어요."));
        }

        @Test
        @DisplayName("요일이 달라도 같은 시간대면 합쳐서 판단한다")
        void peakAcrossDays() throws Exception {
            units(unit("Scanning", 20_000, "2026-10-30T06:30:00+00:00"), // 금 15:30 KST
                    unit("Scanning", 20_000, "2026-10-29T06:30:00+00:00"), // 목 15:30 KST
                    unit("Scanning", 25_000, "2026-10-30T15:10:00+00:00"), // 토 00:10 KST
                    unit("Scanning", 35_000, "2026-10-30T12:10:00+00:00")); // 금 21:10 KST

            card("time-patterns").andExpect(jsonPath("$.data.note.analysis").value(
                    "웹 탐색은 주로 **오후 3시 ~ 오후 6시**에 진행됩니다. 이 시간대에 가장 많은 정보 탐색과 읽기가 이루어지고 있어요."));
        }

        @Test
        @DisplayName("어느 구간도 30% 가 안 되면 '하루 전반' 문구다")
        void noPeak() throws Exception {
            units(unit("Scanning", 29_000, "2026-10-30T15:10:00+00:00"),
                    unit("Scanning", 29_000, "2026-10-31T00:10:00+00:00"),
                    unit("Scanning", 21_000, "2026-10-30T03:10:00+00:00"),
                    unit("Scanning", 21_000, "2026-10-30T11:10:00+00:00"));

            card("time-patterns")
                    .andExpect(jsonPath("$.data.note.analysis").value(
                            "특정 시간대에 집중되기보다, 하루 전반에 걸쳐 웹 탐색이 이루어지고 있어요. 집중이 잘 되는 시간대를 활용해 중요한 웹 탐색을 배치해보세요."))
                    .andExpect(jsonPath("$.data.note.recommendation").value(nullValue()));
        }

        @Test
        @DisplayName("체류 60초 이상 구간 중 정독 비율이 가장 높은 구간을 추천한다")
        void focusNote() throws Exception {
            units(unit("Deep Reading", 59_000, "2026-10-30T15:10:00+00:00"), // 00시 구간 100% 지만 60초 미만
                    unit("Reading", 68_000, "2026-10-31T00:30:00+00:00"), // 09시 구간 68%
                    unit("Scanning", 32_000, "2026-10-31T00:40:00+00:00"),
                    unit("Reading", 30_000, "2026-10-30T07:10:00+00:00"), // 15시 구간 50%
                    unit("Scanning", 30_000, "2026-10-30T07:20:00+00:00"));

            card("time-patterns").andExpect(jsonPath("$.data.note.recommendation").value(
                    "**오전 9시 ~ 오후 12시**에 집중도가 가장 높아요. (정독 비율 68%로 가장 높은 시간대) 중요한 학습은 이 시간대를 활용해보세요."));
        }

        @Test
        @DisplayName("밤 구간은 오후 9시 ~ 오전 0시로 쓴다")
        void lastSlotLabel() throws Exception {
            units(unit("Deep Reading", 60_000, "2026-10-30T12:10:00+00:00"));

            card("time-patterns").andExpect(jsonPath("$.data.note.analysis").value(
                    "웹 탐색은 주로 **오후 9시 ~ 오전 0시**에 진행됩니다. 이 시간대에 가장 많은 정보 탐색과 읽기가 이루어지고 있어요."));
        }

        @Test
        @DisplayName("정독 유닛이 없으면 추천 문구가 없다")
        void noFocus() throws Exception {
            units(unit("Scanning", 120_000, "2026-10-30T12:10:00+00:00"));

            card("time-patterns").andExpect(jsonPath("$.data.note.recommendation").value(nullValue()));
        }
    }

    // ---------- 시드 ----------

    private static LocalDateTime at(String time) {
        return LocalDateTime.parse(time);
    }

    private BrowsingSession completed(String startedAt, String endedAt, int pageCount) {
        return session(me, "세션 " + startedAt, at(startedAt), endedAt == null ? null : at(endedAt), pageCount,
                false, false, List.of(), List.of(), null);
    }

    /** AI 결과 저장과 같이 채운다. 분석 화면이 읽지 않는 항목은 비워 둔다 */
    private void analyzed(BrowsingSession session, Map<String, Object> summary, List<Map<String, Object>> pageVisits,
            List<Map<String, Object>> units) {
        SessionResult result = new SessionResult(session.getId(), session.getUserId());
        result.replace(null, List.of(), units, summary == null ? Map.of() : summary, List.of(), List.of(), "user", "A");
        result.replacePageVisits(pageVisits);
        results.saveAndFlush(result);
    }

    /** 집계에 들어가면 바로 드러나는 분석 결과 */
    private void noise(BrowsingSession session) {
        analyzed(session, summary(100, 0, 0, 0), List.of(visit("https://reuters.com/noise", 1_000, 100)),
                List.of(unit("Scanning", 900_000, "2026-10-31T00:10:00+00:00")));
    }

    /** output.py _summary: 라벨별 유닛 수 · 체류시간 합. 체류시간은 일부러 유닛 수와 다른 비율로 둔다 */
    private static Map<String, Object> summary(int idle, int scanning, int reading, int deepReading) {
        return Map.of(
                "Idle", Map.of("units", idle, "dwellMs", 4_000),
                "Scanning", Map.of("units", scanning, "dwellMs", 3_000),
                "Reading", Map.of("units", reading, "dwellMs", 2_000),
                "Deep Reading", Map.of("units", deepReading, "dwellMs", 1_000));
    }

    /** 체류시간만 쓰는 요약. 유닛 수는 전부 1 */
    private static Map<String, Object> dwell(int idle, int scanning, int reading, int deepReading) {
        return Map.of(
                "Idle", Map.of("units", 1, "dwellMs", idle),
                "Scanning", Map.of("units", 1, "dwellMs", scanning),
                "Reading", Map.of("units", 1, "dwellMs", reading),
                "Deep Reading", Map.of("units", 1, "dwellMs", deepReading));
    }

    /** output.py page_visits_output */
    private static Map<String, Object> visit(String url, int dwellMs, int visitCount) {
        return Map.of("pageId", url, "url", url, "dwellMs", dwellMs, "visitCount", visitCount);
    }

    /** output.py analysis_units_output 의 유닛 한 개 (쓰는 필드만) */
    private static Map<String, Object> unit(String label, Object dwellMs, String firstSeenAt) {
        return Map.of("clusterLabel", label, "dwellMs", dwellMs, "firstSeenAt", firstSeenAt);
    }
}
