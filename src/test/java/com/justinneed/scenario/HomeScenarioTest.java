package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.taggroup.domain.TagGroup;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 홈 대시보드 (GET /home). 결정 내용은 기능 트래킹표 '### 2. 홈' 표 참고.
 * 세션 결과는 AI 출력(pipeline/output.py · mindmap/builder.py)과 같은 모양으로 넣는다.
 *
 * <p>시각은 Clock 을 고정해 쓴다: KST 2026-10-31(토) 10:30. 최근 7일 = 10.25 ~ 10.31, 지난주 = 10.18 ~ 10.24.
 * Clock 을 바꾸면 컨텍스트가 따로 뜨므로 DB 도 따로 쓴다.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:home-scenario;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@DisplayName("홈 대시보드 (GET /home)")
class HomeScenarioTest extends ScenarioTestSupport {

    private static final Instant NOW = Instant.parse("2026-10-31T01:30:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-31");

    @MockitoBean
    private Clock clock;

    @Autowired
    private SessionResultRepository results;

    @Autowired
    private TagGroupRepository tagGroups;

    private Member me;

    @BeforeEach
    void setUp() {
        given(clock.instant()).willReturn(NOW);
        me = member("sprout");
    }

    // ---------- 응답 전체 ----------

    @Test
    @DisplayName("응답 전체: 세션 · 분석 결과가 FE HomeData 묶음 하나로 온다")
    void wholeResponse() throws Exception {
        BrowsingSession todaySession = completed("2026-10-31T09:00:00", "2026-10-31T09:40:00", "자바", "스프링", "JPA");
        analyzed(todaySession,
                mindmap(List.of(node("root:1", "root", "스프링 데이터"), node("topic:0", "topic", "연관관계 매핑"),
                                node("https://a#p1", "unit", "N+1 문제"), node("https://a#p2", "unit", "페치 조인")),
                        List.of(hierarchy("root:1", "topic:0"), hierarchy("topic:0", "https://a#p1"),
                                hierarchy("topic:0", "https://a#p2"), related("https://a#p1", "https://a#p2"))),
                readingUnits(12, 18, 30, 60),
                List.of(page("https://docs.spring.io/spring-data/jpa/reference/", 600_000),
                        page("https://www.baeldung.com/jpa-entities", 300_000),
                        page("https://stackoverflow.com/questions/1", 240_000)));
        BrowsingSession yesterday = completed("2026-10-30T20:00:00", "2026-10-30T20:30:00", "JPA", "스프링");
        analyzed(yesterday,
                mindmap(List.of(node("root:1", "root", "스프링데이터"), node("topic:0", "topic", "페치 전략"),
                                node("https://b#p1", "unit", "페치조인")),
                        List.of(hierarchy("root:1", "topic:0"), hierarchy("topic:0", "https://b#p1"))),
                readingUnits(0, 10, 10, 30),
                List.of(page("https://velog.io/@dev/fetch-join", 200_000)));
        BrowsingSession lastWeek = completed("2026-10-21T10:00:00", "2026-10-21T10:20:00", "JPA", "파리");
        analyzed(lastWeek, chain("파리 여행", "항공권", "직항"), readingUnits(0, 0, 30, 10), List.of());
        BrowsingSession noResult = completed("2026-09-21T10:00:00", "2026-09-21T10:30:00", "파리");
        TagGroup group = tagGroups.saveAndFlush(new TagGroup(me.getId(), "백엔드", List.of("JPA", "스프링"), 0));

        home().andExpect(content().json("""
                {
                  "success": true,
                  "message": null,
                  "data": {
                    "graph": {
                      "nodes": [
                        {"id": "core", "label": "내 탐색", "size": 28, "group": "core"},
                        {"id": "n1", "label": "스프링 데이터", "size": 18, "group": "city"},
                        {"id": "n2", "label": "파리 여행", "size": 16, "group": "city"},
                        {"id": "n3", "label": "연관관계 매핑", "size": 12, "group": "logi"},
                        {"id": "n4", "label": "페치 전략", "size": 12, "group": "logi"},
                        {"id": "n5", "label": "항공권", "size": 12, "group": "logi"},
                        {"id": "n6", "label": "페치 조인", "size": 9, "group": "sub"},
                        {"id": "n7", "label": "N+1 문제", "size": 7, "group": "sub"},
                        {"id": "n8", "label": "직항", "size": 7, "group": "sub"}
                      ],
                      "edges": [
                        ["core", "n1"], ["core", "n2"],
                        ["n1", "n3"], ["n3", "n7"], ["n3", "n6"],
                        ["n1", "n4"], ["n4", "n6"],
                        ["n2", "n5"], ["n5", "n8"]
                      ],
                      "nodeMeta": {
                        "n1": {"seen": "today", "tags": ["자바", "스프링", "JPA"], "sessionId": %1$d},
                        "n2": {"seen": "month", "tags": ["JPA", "파리"], "sessionId": %3$d},
                        "n3": {"seen": "today", "tags": ["자바", "스프링", "JPA"], "sessionId": %1$d},
                        "n4": {"seen": "week", "tags": ["JPA", "스프링"], "sessionId": %2$d},
                        "n5": {"seen": "month", "tags": ["JPA", "파리"], "sessionId": %3$d},
                        "n6": {"seen": "today", "tags": ["자바", "스프링", "JPA"], "sessionId": %1$d},
                        "n7": {"seen": "today", "tags": ["자바", "스프링", "JPA"], "sessionId": %1$d},
                        "n8": {"seen": "month", "tags": ["JPA", "파리"], "sessionId": %3$d}
                      },
                      "tagGroups": [{"id": %5$d, "name": "백엔드", "tags": ["JPA", "스프링"]}],
                      "filterTags": ["JPA", "스프링", "파리", "자바"]
                    },
                    "stats": {
                      "totalNodes": 8,
                      "nodeDelta": 5,
                      "hashtagCount": 4,
                      "weekSessions": 2,
                      "sessionDelta": 1,
                      "avgSessionMinutes": 35,
                      "deepReadingPct": 53,
                      "deepReadingDelta": 28
                    },
                    "weekActivity": {
                      "range": "10.25 — 10.31",
                      "days": [
                        {"day": "일", "minutes": 0}, {"day": "월", "minutes": 0}, {"day": "화", "minutes": 0},
                        {"day": "수", "minutes": 0}, {"day": "목", "minutes": 0}, {"day": "금", "minutes": 30},
                        {"day": "토", "minutes": 40}
                      ]
                    },
                    "topTags": [
                      {"tag": "JPA", "count": 3}, {"tag": "스프링", "count": 2},
                      {"tag": "파리", "count": 2}, {"tag": "자바", "count": 1}
                    ],
                    "todayTimeline": [
                      {
                        "startTime": "09:00",
                        "endTime": "09:40",
                        "tags": ["자바", "스프링", "JPA"],
                        "sites": ["docs.spring.io", "baeldung.com", "stackoverflow.com"]
                      }
                    ],
                    "weekSessions": [
                      {
                        "dayOffset": -1,
                        "startedAt": "20:00",
                        "tags": ["JPA", "스프링"],
                        "visits": [{"domain": "velog.io", "dwellSec": 200}]
                      },
                      {
                        "dayOffset": 0,
                        "startedAt": "09:00",
                        "tags": ["자바", "스프링", "JPA"],
                        "visits": [
                          {"domain": "docs.spring.io", "dwellSec": 600},
                          {"domain": "baeldung.com", "dwellSec": 300},
                          {"domain": "stackoverflow.com", "dwellSec": 240}
                        ]
                      }
                    ],
                    "sessions": [
                      {"id": %1$d, "title": "세션 2026-10-31T09:00:00", "startedAt": "2026-10-31T09:00:00",
                       "endedAt": "2026-10-31T09:40:00", "pageCount": 1, "status": "COMPLETED",
                       "isFavorite": false, "isPublic": false, "tags": ["자바", "스프링", "JPA"]},
                      {"id": %2$d, "title": "세션 2026-10-30T20:00:00", "startedAt": "2026-10-30T20:00:00",
                       "endedAt": "2026-10-30T20:30:00", "pageCount": 1, "status": "COMPLETED",
                       "isFavorite": false, "isPublic": false, "tags": ["JPA", "스프링"]},
                      {"id": %3$d, "title": "세션 2026-10-21T10:00:00", "startedAt": "2026-10-21T10:00:00",
                       "endedAt": "2026-10-21T10:20:00", "pageCount": 1, "status": "COMPLETED",
                       "isFavorite": false, "isPublic": false, "tags": ["JPA", "파리"]},
                      {"id": %4$d, "title": "세션 2026-09-21T10:00:00", "startedAt": "2026-09-21T10:00:00",
                       "endedAt": "2026-09-21T10:30:00", "pageCount": 1, "status": "COMPLETED",
                       "isFavorite": false, "isPublic": false, "tags": ["파리"]}
                    ]
                  }
                }
                """.formatted(todaySession.getId(), yesterday.getId(), lastWeek.getId(), noResult.getId(),
                group.getId()), JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("세션이 없는 회원: 오류가 아니라 0 · 7일 빈 활동 · 빈 목록이 오고, 그래프에는 중심 노드도 두지 않는다")
    void emptyMember() throws Exception {
        home().andExpect(content().json("""
                {
                  "success": true,
                  "message": null,
                  "data": {
                    "graph": {"nodes": [], "edges": [], "nodeMeta": {}, "tagGroups": [], "filterTags": []},
                    "stats": {
                      "totalNodes": 0, "nodeDelta": 0, "hashtagCount": 0, "weekSessions": 0, "sessionDelta": 0,
                      "avgSessionMinutes": 0, "deepReadingPct": 0, "deepReadingDelta": 0
                    },
                    "weekActivity": {
                      "range": "10.25 — 10.31",
                      "days": [
                        {"day": "일", "minutes": 0}, {"day": "월", "minutes": 0}, {"day": "화", "minutes": 0},
                        {"day": "수", "minutes": 0}, {"day": "목", "minutes": 0}, {"day": "금", "minutes": 0},
                        {"day": "토", "minutes": 0}
                      ]
                    },
                    "topTags": [],
                    "todayTimeline": [],
                    "weekSessions": [],
                    "sessions": []
                  }
                }
                """, JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("토큰 없이 부르면 401")
    void unauthorized() throws Exception {
        mvc.perform(get("/home"))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해 주세요."));
    }

    // ---------- 대상 세션 ----------

    @Test
    @DisplayName("다른 회원 · 삭제 · 진행 중 · 실패 세션은 어떤 항목에도 들어가지 않는다")
    void onlyMyCompletedSessions() throws Exception {
        BrowsingSession mine = completed("2026-10-31T09:00:00", "2026-10-31T09:30:00", "내태그");
        analyzed(mine, chain("내 루트", "내 주제", "내 유닛"), readingUnits(0, 0, 0, 100),
                List.of(page("https://mine.example/", 1_000)));

        Member other = member("wanderer");
        BrowsingSession others = session(other, "남의 세션", at("2026-10-31T09:00:00"), at("2026-10-31T09:30:00"), 1,
                false, false, List.of("남의태그"), List.of(), null);
        analyzed(others, chain("남의 루트", "남의 주제", "남의 유닛"), readingUnits(0, 0, 100, 0),
                List.of(page("https://other.example/", 1_000)));
        tagGroups.saveAndFlush(new TagGroup(other.getId(), "남의 그룹", List.of("남의태그"), 0));

        BrowsingSession deleted = completed("2026-10-31T08:00:00", "2026-10-31T08:30:00", "삭제태그");
        analyzed(deleted, chain("삭제 루트", "삭제 주제", "삭제 유닛"), readingUnits(0, 0, 100, 0),
                List.of(page("https://deleted.example/", 1_000)));
        deleted.delete();
        sessions.saveAndFlush(deleted);

        for (SessionStatus status : List.of(SessionStatus.COLLECTING, SessionStatus.READY, SessionStatus.ANALYZING,
                SessionStatus.FAILED)) {
            BrowsingSession notDone = sessionWithStatus(me, status.name(), "2026-10-31T07:00:00",
                    "2026-10-31T07:30:00", status, List.of("미완료태그"));
            analyzed(notDone, chain(status + " 루트", status + " 주제", status + " 유닛"), readingUnits(0, 0, 100, 0),
                    List.of(page("https://not-done.example/", 1_000)));
        }

        home()
                .andExpect(jsonPath("$.data.stats.weekSessions").value(1))
                .andExpect(jsonPath("$.data.stats.totalNodes").value(3))
                .andExpect(jsonPath("$.data.stats.hashtagCount").value(1))
                .andExpect(jsonPath("$.data.stats.avgSessionMinutes").value(30))
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(100))
                .andExpect(jsonPath("$.data.graph.nodes[*].label").value(
                        containsInAnyOrder("내 탐색", "내 루트", "내 주제", "내 유닛")))
                .andExpect(jsonPath("$.data.graph.tagGroups", empty()))
                .andExpect(jsonPath("$.data.topTags[*].tag").value(contains("내태그")))
                .andExpect(jsonPath("$.data.todayTimeline", hasSize(1)))
                .andExpect(jsonPath("$.data.todayTimeline[0].sites").value(contains("mine.example")))
                .andExpect(jsonPath("$.data.weekSessions", hasSize(1)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mine))));
    }

    @Test
    @DisplayName("sessions: 완료 세션이 시작 시각 최신순으로 최대 300개 온다. 같은 시각이면 나중에 저장한 세션이 앞")
    void sessionListNewestFirstMax300() throws Exception {
        LocalDateTime base = TODAY.atTime(9, 0);
        BrowsingSession oldest = completed(base.minusDays(400).toString(), null);
        for (int i = 0; i < 299; i++) {
            completed(base.minusDays(i + 1).toString(), null);
        }
        BrowsingSession first = completed(base.toString(), null);
        BrowsingSession second = completed(base.toString(), null);

        ResultActions result = home().andExpect(jsonPath("$.data.sessions", hasSize(300)));
        List<Integer> sessionIds = read(result, "$.data.sessions[*].id");
        assertThat(sessionIds.subList(0, 2)).containsExactly(second.getId().intValue(), first.getId().intValue());
        assertThat(sessionIds).doesNotContain(oldest.getId().intValue());
    }

    @Test
    @DisplayName("graph.tagGroups: 내 태그 그룹이 position 순서로 온다")
    void tagGroupsInPositionOrder() throws Exception {
        TagGroup later = tagGroups.saveAndFlush(new TagGroup(me.getId(), "나중", List.of("파리"), 1));
        TagGroup earlier = tagGroups.saveAndFlush(new TagGroup(me.getId(), "먼저", List.of("JPA", "스프링"), 0));

        home()
                .andExpect(jsonPath("$.data.graph.tagGroups[*].id")
                        .value(contains(ids(earlier.getId(), later.getId()))))
                .andExpect(jsonPath("$.data.graph.tagGroups[0].name").value("먼저"))
                .andExpect(jsonPath("$.data.graph.tagGroups[0].tags").value(contains("JPA", "스프링")));
    }

    // ---------- 기간 경계 ----------

    @Test
    @DisplayName("이번 주 경계: 6일 전 00:00 은 이번 주, 7일 전 23:59 는 지난주, 13일 전 00:00 은 지난주, 14일 전 23:59 는 어디에도 안 든다")
    void weekBoundaries() throws Exception {
        completed("2026-10-25T00:00:00", "2026-10-25T00:30:00");
        completed("2026-10-24T23:59:00", "2026-10-25T00:29:00");
        completed("2026-10-18T00:00:00", "2026-10-18T00:30:00");
        completed("2026-10-17T23:59:00", "2026-10-18T00:29:00");

        home()
                .andExpect(jsonPath("$.data.stats.weekSessions").value(1))
                .andExpect(jsonPath("$.data.stats.sessionDelta").value(-1))
                .andExpect(jsonPath("$.data.weekSessions[*].dayOffset").value(contains(-6)))
                .andExpect(jsonPath("$.data.weekActivity.days[0].minutes").value(30))
                .andExpect(jsonPath("$.data.sessions", hasSize(4)));
    }

    @Test
    @DisplayName("KST 자정 직후 (UTC 는 아직 어제): 오늘은 KST 날짜로 정해진다")
    void todayFollowsKstWhenUtcDateDiffers() throws Exception {
        // UTC 10-31 15:30 = KST 11-01(일) 00:30
        given(clock.instant()).willReturn(Instant.parse("2026-10-31T15:30:00Z"));
        completed("2026-11-01T00:10:00", "2026-11-01T00:20:00");
        completed("2026-10-31T23:50:00", "2026-10-31T23:55:00");
        completed("2026-10-26T00:00:00", "2026-10-26T00:01:00");
        completed("2026-10-25T23:59:00", "2026-10-26T00:00:00");

        home()
                .andExpect(jsonPath("$.data.weekActivity.range").value("10.26 — 11.1"))
                .andExpect(jsonPath("$.data.weekActivity.days[*].day")
                        .value(contains("월", "화", "수", "목", "금", "토", "일")))
                .andExpect(jsonPath("$.data.weekActivity.days[6].minutes").value(10))
                .andExpect(jsonPath("$.data.weekActivity.days[5].minutes").value(5))
                .andExpect(jsonPath("$.data.todayTimeline", hasSize(1)))
                .andExpect(jsonPath("$.data.todayTimeline[0].startTime").value("00:10"))
                .andExpect(jsonPath("$.data.stats.weekSessions").value(3))
                .andExpect(jsonPath("$.data.stats.sessionDelta").value(2))
                .andExpect(jsonPath("$.data.weekSessions[*].dayOffset").value(contains(-6, -1, 0)))
                .andExpect(jsonPath("$.data.weekSessions[*].startedAt").value(contains("00:00", "23:50", "00:10")));
    }

    @Test
    @DisplayName("KST 하루가 막 시작한 순간 (UTC 15:00): 그 시각부터 새 날이다")
    void kstDayStartsAtUtcFifteen() throws Exception {
        given(clock.instant()).willReturn(Instant.parse("2026-10-30T15:00:00Z"));
        completed("2026-10-31T00:00:00", "2026-10-31T00:30:00");

        home()
                .andExpect(jsonPath("$.data.todayTimeline", hasSize(1)))
                .andExpect(jsonPath("$.data.weekActivity.range").value("10.25 — 10.31"));

        given(clock.instant()).willReturn(Instant.parse("2026-10-30T14:59:59Z"));
        home()
                .andExpect(jsonPath("$.data.todayTimeline", empty()))
                .andExpect(jsonPath("$.data.weekActivity.range").value("10.24 — 10.30"));
    }

    // ---------- 세션 길이 ----------

    @Test
    @DisplayName("평균 세션 길이: 종료 시각이 없는 세션은 세션 수에만 들어가고 분 · 평균에는 빠진다. 반올림")
    void averageSkipsSessionsWithoutEnd() throws Exception {
        completed("2026-10-31T09:00:00", "2026-10-31T09:31:00");
        completed("2026-10-31T08:00:00", "2026-10-31T08:30:00");
        completed("2026-10-31T07:00:00", null);

        home()
                .andExpect(jsonPath("$.data.stats.weekSessions").value(3))
                .andExpect(jsonPath("$.data.stats.avgSessionMinutes").value(31))
                .andExpect(jsonPath("$.data.weekActivity.days[6].minutes").value(61));
    }

    @Test
    @DisplayName("평균 세션 길이: 이번 주 세션에 종료 시각이 하나도 없으면 0, 지난주 세션은 평균에 들어가지 않는다")
    void averageIsZeroWithoutEndedSessions() throws Exception {
        completed("2026-10-31T07:00:00", null);
        completed("2026-10-21T07:00:00", "2026-10-21T08:00:00");

        home()
                .andExpect(jsonPath("$.data.stats.weekSessions").value(1))
                .andExpect(jsonPath("$.data.stats.avgSessionMinutes").value(0));
    }

    @Test
    @DisplayName("주간 활동: 분은 그날 세션 초를 더한 뒤 반올림하고, 자정을 넘긴 세션은 시작일에 들어간다")
    void weekActivityMinutes() throws Exception {
        completed("2026-10-31T09:00:00", "2026-10-31T09:01:30");
        completed("2026-10-31T10:00:00", "2026-10-31T10:01:30");
        completed("2026-10-30T09:00:00", "2026-10-30T09:00:29");
        completed("2026-10-29T09:00:00", "2026-10-29T09:00:30");
        completed("2026-10-28T23:50:00", "2026-10-29T00:10:00");

        home()
                .andExpect(jsonPath("$.data.weekActivity.days[*].minutes").value(contains(0, 0, 0, 20, 1, 0, 3)));
    }

    @Test
    @DisplayName("종료 시각이 시작보다 앞서는 잘못된 세션은 길이 0 으로 본다")
    void negativeLengthCountsAsZero() throws Exception {
        completed("2026-10-31T09:00:00", "2026-10-31T08:00:00");
        completed("2026-10-31T10:00:00", "2026-10-31T10:30:00");

        home()
                .andExpect(jsonPath("$.data.weekActivity.days[6].minutes").value(30))
                .andExpect(jsonPath("$.data.stats.avgSessionMinutes").value(15));
    }

    // ---------- Deep Reading ----------

    @Test
    @DisplayName("Deep Reading: 유닛 수 기준이고 Idle 도 분모에 들어가며, 증감은 지난주 대비 %p")
    void deepReadingCountsIdleAndDelta() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null), null, readingUnits(700, 100, 100, 100), List.of());
        analyzed(completed("2026-10-30T09:00:00", null), null, readingUnits(0, 0, 500, 500), List.of());
        analyzed(completed("2026-10-21T09:00:00", null), null, readingUnits(100, 100, 100, 100), List.of());

        home()
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(30))
                .andExpect(jsonPath("$.data.stats.deepReadingDelta").value(5));
    }

    @Test
    @DisplayName("Deep Reading: 체류시간이 아무리 길어도 비율은 유닛 수로만 정해진다")
    void deepReadingIgnoresDwell() throws Exception {
        Map<String, Object> summary = Map.of(
                "Idle", Map.of("units", 9, "dwellMs", 0),
                "Deep Reading", Map.of("units", 1, "dwellMs", 3_600_000));
        analyzed(completed("2026-10-31T09:00:00", null), null, summary, List.of());

        home().andExpect(jsonPath("$.data.stats.deepReadingPct").value(10));
    }

    @Test
    @DisplayName("Deep Reading: 유닛 수가 Integer · Long · Double 어떤 숫자로 와도 합산한다")
    void deepReadingAcceptsAnyNumber() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null), null,
                readingUnits(1_000_000_000.0, 1_000_000_000, 3_000_000_000L, 5_000_000_000L), List.of());

        home().andExpect(jsonPath("$.data.stats.deepReadingPct").value(50));
    }

    @Test
    @DisplayName("Deep Reading: 이번 주 데이터가 없으면 비율 0 · 증감 0, 지난주가 없어도 증감 0")
    void deepReadingWithoutData() throws Exception {
        analyzed(completed("2026-10-21T09:00:00", null), null, readingUnits(0, 0, 0, 100), List.of());
        analyzed(completed("2026-10-31T09:00:00", null), null, readingUnits(0, 0, 0, 0), List.of());
        completed("2026-10-30T09:00:00", null);

        home()
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(0))
                .andExpect(jsonPath("$.data.stats.deepReadingDelta").value(0));
    }

    @Test
    @DisplayName("Deep Reading: 지난주 데이터가 없으면 이번 주 비율만 오고 증감은 0")
    void deepReadingWithoutLastWeek() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null), null, readingUnits(0, 0, 100, 100), List.of());

        home()
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(50))
                .andExpect(jsonPath("$.data.stats.deepReadingDelta").value(0));
    }

    @Test
    @DisplayName("Deep Reading: 비율은 정수로 반올림하고 지난주 비율이 높으면 증감은 음수다")
    void deepReadingRoundsAndNegativeDelta() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null), null, readingUnits(0, 0, 2, 1), List.of());
        analyzed(completed("2026-10-21T09:00:00", null), null, readingUnits(0, 0, 1, 2), List.of());

        home()
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(33))
                .andExpect(jsonPath("$.data.stats.deepReadingDelta").value(-34));
    }

    // ---------- 마인드맵 병합 ----------

    @Test
    @DisplayName("키워드 병합: 공백 · 대소문자를 무시해 한 노드로 합치고 이름은 가장 최근 세션의 첫 표기를 쓴다")
    void keywordsMergeIgnoringSpaceAndCase() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                mindmap(List.of(node("r", "root", "Spring Boot"), node("t", "topic", "SPRING BOOT 설정"),
                                node("u", "unit", "spring  boot  설정"), node("x", "unit", "Spring Boot\n설정")),
                        List.of(hierarchy("r", "t"), hierarchy("t", "u"), hierarchy("t", "x"))),
                null, List.of());
        analyzed(completed("2026-10-30T09:00:00", null),
                mindmap(List.of(node("r", "root", "SPRINGBOOT")), List.of()), null, List.of());
        analyzed(completed("2026-10-29T09:00:00", null),
                mindmap(List.of(node("r", "root", " spring boot ")), List.of()), null, List.of());

        GraphView graph = graph();
        assertThat(graph.labels()).containsExactly("내 탐색", "Spring Boot", "SPRING BOOT 설정");
        assertThat(graph.size("Spring Boot")).isEqualTo(20);
    }

    @Test
    @DisplayName("키워드 병합: 표시 이름의 앞뒤 공백은 지우고 가운데 공백은 그대로 둔다")
    void labelIsStripped() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null), roots("  Spring Boot\n"), null, List.of());

        assertThat(graph().labels()).containsExactly("내 탐색", "Spring Boot");
    }

    @Test
    @DisplayName("키워드 병합: 빈 키워드 · 공백뿐인 키워드는 건너뛰고 그 노드에 달린 엣지도 사라진다")
    void blankKeywordsAreSkipped() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                mindmap(List.of(node("r", "root", "루트"), node("blank", "topic", "   "), node("empty", "topic", ""),
                                node("t", "topic", "주제")),
                        List.of(hierarchy("r", "blank"), hierarchy("r", "empty"), hierarchy("r", "t"))),
                null, List.of());

        GraphView graph = graph();
        assertThat(graph.labels()).containsExactly("내 탐색", "루트", "주제");
        assertThat(graph.edges()).hasSize(2);
    }

    @Test
    @DisplayName("타입 우선순위: 한 키워드가 세션마다 타입이 다르면 root > topic > unit 중 높은 쪽 그룹이고, root 면 중심에 연결된다")
    void highestTypeWins() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                mindmap(List.of(node("t", "topic", "JPA"), node("u", "unit", "주제겸유닛")), List.of(hierarchy("t", "u"))),
                null, List.of());
        analyzed(completed("2026-10-30T09:00:00", null),
                mindmap(List.of(node("r", "root", "jpa"), node("t", "topic", "주제겸유닛"), node("u", "unit", "유닛만")),
                        List.of(hierarchy("r", "t"), hierarchy("t", "u"))),
                null, List.of());

        GraphView graph = graph();
        assertThat(graph.group("JPA")).isEqualTo("city");
        assertThat(graph.group("주제겸유닛")).isEqualTo("logi");
        assertThat(graph.group("유닛만")).isEqualTo("sub");
        assertThat(graph.hasEdge("core", graph.id("JPA"))).isTrue();
        assertThat(graph.edges()).filteredOn(edge -> edge.get(0).equals("core")).hasSize(1);
    }

    @Test
    @DisplayName("노드 크기: 세션이 하나 늘 때마다 +2, 타입별 상한까지 (city 16~24 · logi 12~18 · sub 7~12)")
    void sizeGrowsWithSessionsUpToCap() throws Exception {
        LocalDateTime base = TODAY.atTime(9, 0);
        for (int day = 0; day < 6; day++) {
            List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                    node("r5", "root", "루트다섯이상"), node("t1", "topic", "주제다섯이상")));
            if (day < 4) {
                nodes.add(node("u1", "unit", "유닛넷"));
            }
            if (day < 3) {
                nodes.add(node("t2", "topic", "주제셋"));
                nodes.add(node("u2", "unit", "유닛셋"));
            }
            if (day < 2) {
                nodes.add(node("r2", "root", "루트둘"));
            }
            nodes.add(node("r" + (10 + day), "root", "루트하나" + day));
            nodes.add(node("t" + (10 + day), "topic", "주제하나" + day));
            nodes.add(node("u" + (10 + day), "unit", "유닛하나" + day));
            List<Map<String, Object>> edges = new ArrayList<>();
            for (Map<String, Object> node : nodes) {
                if (!"root".equals(node.get("type"))) {
                    edges.add(hierarchy("r5", (String) node.get("id")));
                }
            }
            analyzed(completed(base.minusDays(day).toString(), null), mindmap(nodes, edges), null, List.of());
        }

        GraphView graph = graph();
        assertThat(graph.size("루트하나0")).isEqualTo(16);
        assertThat(graph.size("루트둘")).isEqualTo(18);
        assertThat(graph.size("루트다섯이상")).isEqualTo(24);
        assertThat(graph.size("주제하나0")).isEqualTo(12);
        assertThat(graph.size("주제셋")).isEqualTo(16);
        assertThat(graph.size("주제다섯이상")).isEqualTo(18);
        assertThat(graph.size("유닛하나0")).isEqualTo(7);
        assertThat(graph.size("유닛셋")).isEqualTo(11);
        assertThat(graph.size("유닛넷")).isEqualTo(12);
        assertThat(graph.size("내 탐색")).isEqualTo(28);
    }

    @Test
    @DisplayName("한 세션에 같은 키워드가 여러 번 나와도 세션 1개로 센다")
    void sameKeywordTwiceInOneSessionCountsOnce() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                mindmap(List.of(node("r", "root", "루트"), node("u1", "unit", "중복"), node("u2", "unit", "중복")),
                        List.of(hierarchy("r", "u1"), hierarchy("r", "u2"))),
                null, List.of());

        GraphView graph = graph();
        assertThat(graph.size("중복")).isEqualTo(7);
        assertThat(graph.edges()).hasSize(2);
    }

    @Test
    @DisplayName("엣지: related 는 빼고, 추가 부모(primary=false)는 넣고, 합쳐져서 생긴 자기 자신 · 중복 · 방향만 다른 엣지는 뺀다")
    void edgeRules() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                mindmap(List.of(node("r", "root", "루트"), node("t", "topic", "JPA"), node("t2", "topic", "다른주제"),
                                node("u", "unit", "페치조인"), node("u2", "unit", "연관없음")),
                        List.of(hierarchy("r", "t"), hierarchy("r", "t2"), hierarchy("t", "u"),
                                extraParent("t2", "u"), hierarchy("t", "u2"), related("u", "t2"),
                                related("r", "u2"))),
                null, List.of());
        analyzed(completed("2026-10-30T09:00:00", null),
                mindmap(List.of(node("r", "root", "루트"), node("t", "topic", "jpa"), node("u", "unit", "JPA "),
                                node("v", "unit", "페치 조인")),
                        List.of(hierarchy("r", "t"), hierarchy("t", "u"), hierarchy("v", "t"))),
                null, List.of());

        GraphView graph = graph();
        assertThat(graph.hasEdge(graph.id("루트"), graph.id("JPA"))).isTrue();
        assertThat(graph.hasEdge(graph.id("다른주제"), graph.id("페치조인"))).isTrue();
        assertThat(graph.hasEdge(graph.id("페치조인"), graph.id("다른주제"))).isTrue();
        assertThat(graph.hasEdge(graph.id("루트"), graph.id("연관없음"))).isFalse();
        assertThat(graph.edges()).hasSize(6);
        assertThat(graph.edges().stream().map(HomeScenarioTest::undirected).collect(Collectors.toSet())).hasSize(6);
        assertThat(graph.edges()).noneMatch(edge -> edge.get(0).equals(edge.get(1)));
    }

    @Test
    @DisplayName("엣지가 없어 core 에 닿지 않는 노드는 그래프에서 빠지지만 누적 노드 수에는 들어간다")
    void isolatedNodesAreDroppedButCounted() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                mindmap(List.of(node("r", "root", "루트"), node("t", "topic", "외톨이"), node("u", "unit", "연결됨")),
                        List.of(hierarchy("r", "u"))),
                null, List.of());

        GraphView graph = graph();
        assertThat(graph.labels()).containsExactly("내 탐색", "루트", "연결됨");
        home().andExpect(jsonPath("$.data.stats.totalNodes").value(3));
    }

    @Test
    @DisplayName("노드가 있어도 어느 것도 core 에 닿지 않으면 그래프는 비고 누적 노드 수만 남는다")
    void noRootMeansEmptyGraph() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                mindmap(List.of(node("t", "topic", "주제"), node("u", "unit", "유닛")), List.of(hierarchy("t", "u"))),
                null, List.of());

        home()
                .andExpect(jsonPath("$.data.graph.nodes", empty()))
                .andExpect(jsonPath("$.data.graph.edges", empty()))
                .andExpect(jsonPath("$.data.stats.totalNodes").value(2));
    }

    // ---------- 150개 제한 ----------

    @Test
    @DisplayName("150개 제한: core 포함 150개, 같은 타입은 세션 많은 순 → 최근 순이고, 끝점이 빠진 엣지 없이 모두 core 에 연결된다")
    void capKeepsMostUsedThenRecentAndStaysConnected() throws Exception {
        List<String> shared = keywords("공통", 10);
        List<String> recent = keywords("최근", 200);
        List<String> old = keywords("예전", 100);
        analyzed(completed("2026-10-31T09:00:00", null),
                tree("루트", "주제", concat(recent, shared)), null, List.of());
        analyzed(completed("2026-10-30T09:00:00", null),
                tree("루트", "주제", concat(old, shared)), null, List.of());

        GraphView graph = graph();
        assertThat(graph.nodes()).hasSize(150);
        assertThat(graph.labels().get(0)).isEqualTo("내 탐색");
        assertThat(graph.labels()).contains("루트", "주제");
        assertThat(graph.labels()).containsAll(shared);
        assertThat(graph.labels()).doesNotContainAnyElementsOf(old);
        assertThat(graph.labels().stream().filter(label -> label.startsWith("최근"))).hasSize(137);
        assertThat(graph.labels().get(3)).isEqualTo("공통001");
        graph.assertConnected();
        home()
                .andExpect(jsonPath("$.data.stats.totalNodes").value(312))
                .andExpect(jsonPath("$.data.stats.nodeDelta").value(312));
    }

    @Test
    @DisplayName("150개 제한: root → topic → unit 순서로 남긴다 (노드 id 는 응답 안에서 n1 부터 빠짐없이)")
    void capPrefersRootThenTopicThenUnit() throws Exception {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (String unit : keywords("유닛", 10)) {
            nodes.add(node(unit, "unit", unit));
            edges.add(hierarchy("root1", unit));
        }
        for (String topic : keywords("주제", 120)) {
            nodes.add(node(topic, "topic", topic));
            edges.add(hierarchy("root1", topic));
        }
        nodes.add(node("root1", "root", "루트0"));
        for (String root : keywords("루트", 39)) {
            nodes.add(node(root, "root", root));
        }
        analyzed(completed("2026-10-31T09:00:00", null), mindmap(nodes, edges), null, List.of());

        GraphView graph = graph();
        assertThat(graph.nodes()).hasSize(150);
        assertThat(graph.labels().stream().filter(label -> label.startsWith("루트"))).hasSize(40);
        assertThat(graph.labels().stream().filter(label -> label.startsWith("주제"))).hasSize(109);
        assertThat(graph.labels().stream().filter(label -> label.startsWith("유닛"))).isEmpty();
        assertThat(graph.ids()).startsWith("core", "n1", "n2").endsWith("n149");
        graph.assertConnected();
        assertThat(graph.meta().keySet()).hasSize(149).doesNotContain("core");
    }

    @Test
    @DisplayName("150개 제한: 부모 유닛이 잘려 core 에 못 닿게 된 유닛끼리는 남기지 않는다")
    void capDropsIslandsCutOffFromCore() throws Exception {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                node("r", "root", "루트"), node("t", "topic", "주제"),
                node("부모", "unit", "힣힣힣힣부모"), node("자식", "unit", "가가가자식"), node("손자", "unit", "가가가손자")));
        List<Map<String, Object>> edges = new ArrayList<>(List.of(
                hierarchy("r", "t"), hierarchy("t", "부모"), hierarchy("부모", "자식"), hierarchy("자식", "손자")));
        for (String unit : keywords("나", 200)) {
            nodes.add(node(unit, "unit", unit));
            edges.add(hierarchy("t", unit));
        }
        analyzed(completed("2026-10-31T09:00:00", null), mindmap(nodes, edges), null, List.of());

        GraphView graph = graph();
        assertThat(graph.labels()).doesNotContain("힣힣힣힣부모", "가가가자식", "가가가손자");
        assertThat(graph.nodes()).hasSize(148);
        graph.assertConnected();
    }

    @Test
    @DisplayName("노드 150개에 딱 맞으면 하나도 자르지 않는다")
    void exactlyAtCapKeepsEverything() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                tree("루트", "주제", keywords("유닛", 147)), null, List.of());

        GraphView graph = graph();
        assertThat(graph.nodes()).hasSize(150);
        graph.assertConnected();
        home().andExpect(jsonPath("$.data.stats.totalNodes").value(149));
    }

    @Test
    @DisplayName("같은 데이터로 두 번 불러도 노드 순서 · id · 엣지가 같다")
    void graphIsDeterministic() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null),
                tree("루트", "주제", keywords("최근", 200)), null, List.of());
        analyzed(completed("2026-10-30T09:00:00", null),
                tree("루트2", "주제2", keywords("예전", 200)), null, List.of());

        GraphView first = graph();
        GraphView second = graph();
        assertThat(second.nodes()).isEqualTo(first.nodes());
        assertThat(second.edges()).isEqualTo(first.edges());
        assertThat(second.meta()).isEqualTo(first.meta());
    }

    // ---------- nodeMeta · 누적 노드 ----------

    @Test
    @DisplayName("seen: 가장 최근에 나온 세션 기준 today · week(6일 전까지) · month(29일 전까지) · all")
    void seenPeriods() throws Exception {
        analyzed(completed("2026-10-31T00:00:00", null), roots("오늘"), null, List.of());
        analyzed(completed("2026-10-30T23:59:00", null), roots("어제"), null, List.of());
        analyzed(completed("2026-10-25T00:00:00", null), roots("육일전"), null, List.of());
        analyzed(completed("2026-10-24T23:59:00", null), roots("칠일전"), null, List.of());
        analyzed(completed("2026-10-02T00:00:00", null), roots("이십구일전"), null, List.of());
        analyzed(completed("2026-10-01T23:59:00", null), roots("삼십일전"), null, List.of());

        GraphView graph = graph();
        assertThat(graph.seen("오늘")).isEqualTo("today");
        assertThat(graph.seen("어제")).isEqualTo("week");
        assertThat(graph.seen("육일전")).isEqualTo("week");
        assertThat(graph.seen("칠일전")).isEqualTo("month");
        assertThat(graph.seen("이십구일전")).isEqualTo("month");
        assertThat(graph.seen("삼십일전")).isEqualTo("all");
    }

    @Test
    @DisplayName("nodeMeta: 태그는 그 노드가 나온 세션들의 합집합(대소문자 무시), sessionId · seen 은 가장 최근 세션, core 는 메타가 없다")
    void nodeMetaUnionsTagsAndPointsToLatestSession() throws Exception {
        BrowsingSession latest = completed("2026-10-30T09:00:00", null, "Spring", "JPA");
        analyzed(latest, roots("공통"), null, List.of());
        BrowsingSession older = completed("2026-09-01T09:00:00", null, "spring", "파리");
        analyzed(older, roots("공통"), null, List.of());
        BrowsingSession unrelated = completed("2026-10-31T09:00:00", null, "다른태그");
        analyzed(unrelated, roots("다른 노드"), null, List.of());

        GraphView graph = graph();
        Map<String, Object> meta = graph.meta().get(graph.id("공통"));
        assertThat(meta.get("seen")).isEqualTo("week");
        assertThat(meta.get("sessionId")).isEqualTo(latest.getId().intValue());
        assertThat(meta.get("tags")).isEqualTo(List.of("Spring", "JPA", "파리"));
        assertThat(graph.meta()).doesNotContainKey("core");
    }

    @Test
    @DisplayName("nodeDelta: 가장 이른 세션이 최근 7일 안인 노드만 센다. 예전에 나왔다가 다시 나온 노드는 신규가 아니다")
    void nodeDeltaCountsFirstAppearanceThisWeek() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null), roots("오늘 처음", "다시 나옴"), null, List.of());
        analyzed(completed("2026-10-25T00:00:00", null), roots("육일전 처음"), null, List.of());
        analyzed(completed("2026-10-24T23:59:00", null), roots("칠일전 처음", "다시 나옴"), null, List.of());

        home()
                .andExpect(jsonPath("$.data.stats.totalNodes").value(4))
                .andExpect(jsonPath("$.data.stats.nodeDelta").value(2));
    }

    // ---------- 해시태그 ----------

    @Test
    @DisplayName("hashtagCount: 전체 완료 세션의 태그를 대소문자 무시로 센다")
    void hashtagCountIsDistinctAcrossAllSessions() throws Exception {
        completed("2026-10-31T09:00:00", null, "Spring", "JPA");
        completed("2026-10-30T09:00:00", null, "spring");
        completed("2020-01-01T09:00:00", null, "JAVA", "SPRING");

        home().andExpect(jsonPath("$.data.stats.hashtagCount").value(3));
    }

    @Test
    @DisplayName("topTags: 세션 수 많은 순 → 더 최근 세션 → 이름순, 표기는 가장 최근 세션, 한 세션 안 중복은 1번, # 없이 온다")
    void topTagsOrdering() throws Exception {
        completed("2026-10-31T09:00:00", null, "다", "나", "Spring", "SPRING");
        completed("2026-10-30T09:00:00", null, "가");
        completed("2026-10-29T09:00:00", null, "spring");

        home()
                .andExpect(jsonPath("$.data.topTags[*].tag").value(contains("Spring", "나", "다", "가")))
                .andExpect(jsonPath("$.data.topTags[*].count").value(contains(2, 1, 1, 1)));
    }

    @Test
    @DisplayName("topTags 는 최대 10개, filterTags 는 같은 순서로 최대 20개")
    void topTagsAndFilterTagsLimits() throws Exception {
        LocalDateTime base = TODAY.atTime(9, 0);
        List<String> tags = keywords("태그", 25);
        for (int i = 0; i < tags.size(); i++) {
            completed(base.minusDays(i).toString(), null, tags.get(i));
        }

        ResultActions result = home()
                .andExpect(jsonPath("$.data.topTags", hasSize(10)))
                .andExpect(jsonPath("$.data.graph.filterTags", hasSize(20)));
        List<String> filterTags = read(result, "$.data.graph.filterTags");
        List<String> topTags = read(result, "$.data.topTags[*].tag");
        assertThat(filterTags).containsExactlyElementsOf(tags.subList(0, 20));
        assertThat(topTags).containsExactlyElementsOf(tags.subList(0, 10));
    }

    // ---------- 오늘 타임라인 ----------

    @Test
    @DisplayName("오늘 타임라인: 오늘 시작한 세션마다 슬롯 하나, 오래된 순. 종료 시각이 없으면 endTime 은 null, 어제 세션은 없다")
    void todayTimelineOrdering() throws Exception {
        completed("2026-10-31T10:00:00", null, "늦은세션");
        completed("2026-10-31T00:00:00", "2026-10-31T00:30:00", "이른세션");
        completed("2026-10-30T23:59:00", "2026-10-31T00:10:00", "어제세션");

        home()
                .andExpect(jsonPath("$.data.todayTimeline", hasSize(2)))
                .andExpect(jsonPath("$.data.todayTimeline[0].startTime").value("00:00"))
                .andExpect(jsonPath("$.data.todayTimeline[0].endTime").value("00:30"))
                .andExpect(jsonPath("$.data.todayTimeline[0].tags").value(contains("이른세션")))
                .andExpect(jsonPath("$.data.todayTimeline[1].startTime").value("10:00"))
                .andExpect(jsonPath("$.data.todayTimeline[1].endTime").value(nullValue()))
                .andExpect(jsonPath("$.data.todayTimeline[1].tags").value(contains("늦은세션")));
    }

    @Test
    @DisplayName("오늘 타임라인 사이트: 같은 도메인의 체류시간을 합쳐 긴 순으로 최대 3개, 같으면 이름순")
    void todayTimelineTopThreeSites() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null), null, null,
                List.of(page("https://b.example/1", 100), page("https://c.example/1", 300),
                        page("https://www.b.example/2", 250), page("https://a.example/1", 300),
                        page("https://d.example/1", 200)));

        home().andExpect(jsonPath("$.data.todayTimeline[0].sites")
                .value(contains("b.example", "a.example", "c.example")));
    }

    // ---------- 이번 주 세션 ----------

    @Test
    @DisplayName("이번 주 세션: 오래된 순으로 오고, 페이지 한 건마다 방문 하나 (같은 도메인이어도 합치지 않는다), 초는 반올림")
    void weekSessionsVisits() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", null, "오늘"), null, null,
                List.of(page("https://a.example/1", 1_499), page("https://a.example/2", 1_500),
                        page("https://b.example/", 180_001)));
        analyzed(completed("2026-10-25T23:30:00", null, "오래됨"), null, null, List.of());
        completed("2026-10-24T09:00:00", null, "지난주");

        home()
                .andExpect(jsonPath("$.data.weekSessions[*].dayOffset").value(contains(-6, 0)))
                .andExpect(jsonPath("$.data.weekSessions[*].startedAt").value(contains("23:30", "09:00")))
                .andExpect(jsonPath("$.data.weekSessions[0].tags").value(contains("오래됨")))
                .andExpect(jsonPath("$.data.weekSessions[0].visits", empty()))
                .andExpect(jsonPath("$.data.weekSessions[1].visits[*].domain")
                        .value(contains("a.example", "a.example", "b.example")))
                .andExpect(jsonPath("$.data.weekSessions[1].visits[*].dwellSec").value(contains(1, 2, 180)));
    }

    @Test
    @DisplayName("도메인: host 소문자, 앞의 www. 와 포트 제거. URL 이 아니거나 비어 있으면 건너뛴다")
    void domainParsing() throws Exception {
        Map<String, Object> noUrl = new LinkedHashMap<>(page("https://skip.example/", 1_000));
        noUrl.remove("url");
        Map<String, Object> nullUrl = new LinkedHashMap<>(page("https://skip.example/", 1_000));
        nullUrl.put("url", null);
        Map<String, Object> numberUrl = new LinkedHashMap<>(page("https://skip.example/", 1_000));
        numberUrl.put("url", 123);
        analyzed(completed("2026-10-31T09:00:00", null), null, null,
                List.of(page("https://WWW.Example.COM:8443/path", 1_000),
                        page("https://user:secret@blog.example.com/a", 1_000),
                        page("http://www2.example.org/", 1_000),
                        page("https://www.www.example.net/", 1_000),
                        page("https://search.example.kr/?q=a|b{c}^d", 1_000),
                        page("  https://trim.example/  ", 1_000),
                        page("https://ko.wikipedia.org/wiki/한글", 1_000),
                        page("not a url", 1_000),
                        page("not-a-url", 1_000),
                        page("", 1_000),
                        page("   ", 1_000),
                        page("https://", 1_000),
                        page("https://exa mple.com/", 1_000),
                        page("chrome://extensions", 1_000),
                        page("about:blank", 1_000),
                        page("file:///Users/me/a.pdf", 1_000),
                        page("mailto:me@example.com", 1_000),
                        noUrl, nullUrl, numberUrl));

        home().andExpect(jsonPath("$.data.weekSessions[0].visits[*].domain").value(contains(
                "example.com", "blog.example.com", "www2.example.org", "www.example.net", "search.example.kr",
                "trim.example", "ko.wikipedia.org")));
    }

    // ---------- 분석 결과가 없거나 어긋난 세션 ----------

    @Test
    @DisplayName("mindmap 이 null 인 세션: 세션 통계 · 사이트 · 체류시간은 그대로 쓰고 노드만 없다")
    void nullMindmap() throws Exception {
        analyzed(completed("2026-10-31T09:00:00", "2026-10-31T09:30:00", "태그"), null, readingUnits(0, 0, 100, 100),
                List.of(page("https://a.example/", 1_000)));

        home()
                .andExpect(jsonPath("$.data.stats.weekSessions").value(1))
                .andExpect(jsonPath("$.data.stats.avgSessionMinutes").value(30))
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(50))
                .andExpect(jsonPath("$.data.stats.totalNodes").value(0))
                .andExpect(jsonPath("$.data.graph.nodes", empty()))
                .andExpect(jsonPath("$.data.todayTimeline[0].sites").value(contains("a.example")));
    }

    @Test
    @DisplayName("session_results 행이 없는 완료 세션: 세션 통계 · 태그에는 들어가고 노드 · 사이트는 없다")
    void missingResultRow() throws Exception {
        completed("2026-10-31T09:00:00", "2026-10-31T09:30:00", "태그");

        home()
                .andExpect(jsonPath("$.data.stats.weekSessions").value(1))
                .andExpect(jsonPath("$.data.stats.avgSessionMinutes").value(30))
                .andExpect(jsonPath("$.data.stats.hashtagCount").value(1))
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(0))
                .andExpect(jsonPath("$.data.graph.nodes", empty()))
                .andExpect(jsonPath("$.data.weekActivity.days[6].minutes").value(30))
                .andExpect(jsonPath("$.data.todayTimeline[0].sites", empty()))
                .andExpect(jsonPath("$.data.todayTimeline[0].tags").value(contains("태그")))
                .andExpect(jsonPath("$.data.weekSessions[0].visits", empty()))
                .andExpect(jsonPath("$.data.sessions", hasSize(1)));
    }

    @Test
    @DisplayName("AI JSON 이 어긋나도 500 없이 읽을 수 있는 부분만 쓴다 (형식이 다른 값 · 빠진 키 · 모르는 타입)")
    void malformedJsonIsSkipped() throws Exception {
        Map<String, Object> malformedMindmap = new LinkedHashMap<>();
        malformedMindmap.put("nodes", Arrays.asList(
                "문자열 노드",
                null,
                Map.of("id", "x"),
                Map.of("id", "y", "type", "topic"),
                Map.of("id", "z", "type", "topic", "keyword", 123),
                Map.of("id", "w", "type", "모르는타입", "keyword", "모르는타입 노드"),
                Map.of("type", "unit", "keyword", "id 없는 유닛"),
                node("r", "root", "멀쩡한 루트"),
                node("u", "unit", "멀쩡한 유닛")));
        malformedMindmap.put("edges", Arrays.asList(
                "문자열 엣지",
                null,
                Map.of("from", "r"),
                Map.of("to", "u", "type", "hierarchy"),
                Map.of("from", "r", "to", "없는노드", "type", "hierarchy"),
                Map.of("from", 1, "to", 2, "type", "hierarchy"),
                Map.of("from", "r", "to", "u"),
                hierarchy("r", "u")));
        Map<String, Object> malformedSummary = new LinkedHashMap<>();
        malformedSummary.put("Idle", "많음");
        malformedSummary.put("Scanning", Map.of("dwellMs", 100));
        malformedSummary.put("Reading", Map.of("units", "3"));
        malformedSummary.put("Deep Reading", Map.of("units", 1));
        Map<String, Object> noDwell = new LinkedHashMap<>(page("https://a.example/", 1_000));
        noDwell.remove("dwellMs");
        Map<String, Object> textDwell = new LinkedHashMap<>(page("https://b.example/", 1_000));
        textDwell.put("dwellMs", "1000");
        Map<String, Object> negativeDwell = new LinkedHashMap<>(page("https://c.example/", 1_000));
        negativeDwell.put("dwellMs", -5_000);
        analyzed(completed("2026-10-31T09:00:00", null), malformedMindmap, malformedSummary,
                Arrays.asList(noDwell, null, textDwell, negativeDwell));
        analyzed(completed("2026-10-30T09:00:00", null), Map.of("nodes", "목록 아님", "edges", 3), Map.of(), List.of());
        analyzed(completed("2026-10-29T09:00:00", null), Map.of(), Map.of("Deep Reading", List.of()), List.of());

        GraphView graph = graph();
        assertThat(graph.labels()).containsExactly("내 탐색", "멀쩡한 루트", "멀쩡한 유닛");
        home()
                .andExpect(jsonPath("$.data.stats.totalNodes").value(3))
                .andExpect(jsonPath("$.data.stats.deepReadingPct").value(100))
                .andExpect(jsonPath("$.data.stats.weekSessions").value(3))
                .andExpect(jsonPath("$.data.weekSessions[2].visits[*].dwellSec").value(contains(0, 0, 0)))
                .andExpect(jsonPath("$.data.todayTimeline[0].sites")
                        .value(contains("a.example", "b.example", "c.example")));
    }

    // ---------- 호출 · 시드 ----------

    private ResultActions home() throws Exception {
        return mvc.perform(get("/home").with(as(me)))
                .andExpect(okEnvelope());
    }

    private GraphView graph() throws Exception {
        ResultActions result = home();
        return new GraphView(
                read(result, "$.data.graph.nodes"),
                read(result, "$.data.graph.edges"),
                read(result, "$.data.graph.nodeMeta"));
    }

    private static LocalDateTime at(String dateTime) {
        return dateTime == null ? null : LocalDateTime.parse(dateTime);
    }

    private BrowsingSession completed(String startedAt, String endedAt, String... tags) {
        return session(me, "세션 " + startedAt, at(startedAt), at(endedAt), 1, false, false, List.of(tags), List.of(),
                null);
    }

    /** AI 결과 저장과 같이 replace 로 채운다. 홈이 읽지 않는 항목은 비워 둔다. summary 가 null 이면 빈 요약. */
    private void analyzed(BrowsingSession session, Map<String, Object> mindmap, Map<String, Object> summary,
            List<Map<String, Object>> pages) {
        SessionResult result = new SessionResult(session.getId(), session.getUserId());
        Map<String, Object> analysisSummary = summary == null ? Map.of() : summary;
        result.replace(mindmap, List.of(), List.of(), analysisSummary, pages, List.of(), "user", "A");
        results.saveAndFlush(result);
    }

    // ---------- AI 출력 모양 ----------

    /** builder.py build_mindmap 결과: 노드 · 엣지 + rootSource · structureSource · repairs. */
    private static Map<String, Object> mindmap(List<?> nodes, List<?> edges) {
        Map<String, Object> mindmap = new LinkedHashMap<>();
        mindmap.put("rootSource", "llm");
        mindmap.put("structureSource", "llm");
        mindmap.put("nodes", nodes);
        mindmap.put("edges", edges);
        mindmap.put("repairs", List.of());
        return mindmap;
    }

    /** _choose_keywords · _add_child_counts 를 거친 노드. id 는 root:1 · topic:0 · unitKey(URL#pid). */
    private static Map<String, Object> node(String id, String type, String keyword) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("keyword", keyword);
        node.put("keywordCandidates", List.of(keyword));
        node.put("unitRef", null);
        node.put("mergedFrom", List.of());
        node.put("score", 0.8125);
        node.put("childCount", 1);
        return node;
    }

    private static Map<String, Object> hierarchy(String from, String to) {
        return Map.of("from", from, "to", to, "type", "hierarchy", "primary", true, "order", 0);
    }

    private static Map<String, Object> extraParent(String from, String to) {
        return Map.of("from", from, "to", to, "type", "hierarchy", "primary", false);
    }

    private static Map<String, Object> related(String from, String to) {
        return Map.of("from", from, "to", to, "type", "related");
    }

    /** 루트 → 주제 → 유닛 한 줄. */
    private static Map<String, Object> chain(String root, String topic, String unit) {
        return tree(root, topic, List.of(unit));
    }

    /** 루트 하나 아래 주제 하나, 그 아래 유닛들. */
    private static Map<String, Object> tree(String root, String topic, List<String> units) {
        List<Map<String, Object>> nodes = new ArrayList<>(List.of(
                node("root:1", "root", root), node("topic:0", "topic", topic)));
        List<Map<String, Object>> edges = new ArrayList<>(List.of(hierarchy("root:1", "topic:0")));
        for (int i = 0; i < units.size(); i++) {
            String id = "https://example.com/post#p" + i;
            nodes.add(node(id, "unit", units.get(i)));
            edges.add(hierarchy("topic:0", id));
        }
        return mindmap(nodes, edges);
    }

    private static Map<String, Object> roots(String... keywords) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int i = 0; i < keywords.length; i++) {
            nodes.add(node("root:" + (i + 1), "root", keywords[i]));
        }
        return mindmap(nodes, List.of());
    }

    /** output.py _summary: 라벨별 유닛 수 · 체류시간 합. 체류시간은 일부러 유닛 수와 다른 비율로 둔다. */
    private static Map<String, Object> readingUnits(Object idle, Object scanning, Object reading, Object deepReading) {
        return Map.of(
                "Idle", Map.of("units", idle, "dwellMs", 4_000),
                "Scanning", Map.of("units", scanning, "dwellMs", 3_000),
                "Reading", Map.of("units", reading, "dwellMs", 2_000),
                "Deep Reading", Map.of("units", deepReading, "dwellMs", 1_000));
    }

    /** output.py pages_output 에 withCategory 가 category 를 붙인 모양. */
    private static Map<String, Object> page(String url, Object dwellMs) {
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("pageId", url);
        page.put("url", url);
        page.put("title", "글 제목");
        page.put("firstVisitedAt", "2026-10-31T00:00:00+00:00");
        page.put("lastVisitedAt", "2026-10-31T00:10:00+00:00");
        page.put("dwellMs", dwellMs);
        page.put("category", "ETC");
        return page;
    }

    /** 정렬 순서대로 번호가 붙는 키워드: 앞말001, 앞말002, … */
    private static List<String> keywords(String prefix, int count) {
        List<String> keywords = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            keywords.add("%s%03d".formatted(prefix, i));
        }
        return keywords;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> merged = new ArrayList<>(first);
        merged.addAll(second);
        return merged;
    }

    private static Set<String> undirected(List<String> edge) {
        return new HashSet<>(edge);
    }

    /** 응답 그래프를 라벨로 찾아 본다. */
    private record GraphView(
            List<Map<String, Object>> nodes,
            List<List<String>> edges,
            Map<String, Map<String, Object>> meta
    ) {

        List<String> ids() {
            return nodes.stream().map(node -> (String) node.get("id")).toList();
        }

        List<String> labels() {
            return nodes.stream().map(node -> (String) node.get("label")).toList();
        }

        private Map<String, Object> node(String label) {
            return nodes.stream()
                    .filter(node -> label.equals(node.get("label")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("노드 없음: " + label));
        }

        String id(String label) {
            return (String) node(label).get("id");
        }

        int size(String label) {
            return (Integer) node(label).get("size");
        }

        String group(String label) {
            return (String) node(label).get("group");
        }

        String seen(String label) {
            return (String) meta.get(id(label)).get("seen");
        }

        boolean hasEdge(String a, String b) {
            return edges.stream().anyMatch(edge -> new HashSet<>(edge).equals(Set.of(a, b)));
        }

        /** 모든 엣지의 끝점이 노드에 있고, 모든 노드가 core 에서 닿는다. */
        void assertConnected() {
            Set<String> ids = new HashSet<>(ids());
            assertThat(edges).allSatisfy(edge -> assertThat(ids).containsAll(edge));

            Map<String, List<String>> neighbors = new HashMap<>();
            for (List<String> edge : edges) {
                neighbors.computeIfAbsent(edge.get(0), key -> new ArrayList<>()).add(edge.get(1));
                neighbors.computeIfAbsent(edge.get(1), key -> new ArrayList<>()).add(edge.get(0));
            }
            Set<String> reached = new HashSet<>(Set.of("core"));
            List<String> queue = new ArrayList<>(List.of("core"));
            while (!queue.isEmpty()) {
                for (String neighbor : neighbors.getOrDefault(queue.remove(0), List.of())) {
                    if (reached.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            assertThat(reached).containsExactlyInAnyOrderElementsOf(ids);
        }
    }
}
