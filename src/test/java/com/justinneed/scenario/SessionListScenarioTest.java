package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 세션 목록 화면 (/sessions). FE: src/features/sessions/SessionsView.tsx, SessionCard.tsx.
 * 시드: FE 목 MOCK_SESSIONS 7개 (로마 3박 4일 동선 짜기 ~ 프라하 1박 일정 짜기).
 * 응답: data = { sessions: [카드...], hasNext, totalCount, favoriteCount }.
 */
@DisplayName("세션 목록 화면")
class SessionListScenarioTest extends ScenarioTestSupport {

    private static final String INVALID_PAGE = "페이지 값이 올바르지 않습니다.";
    private static final String INVALID_REQUEST = "요청 형식이 올바르지 않습니다.";

    @Test
    @DisplayName("세션 목록은 종료 시각 최신순(REC-10-1)으로 온다 — 저장 순서와 상관없다")
    void listIsNewestFirst() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(7)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.newestFirst().toArray(BrowsingSession[]::new)))))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains(
                        "로마 3박 4일 동선 짜기",
                        "유럽 항공권 비교 (인천→로마 IN, 프라하→인천 OUT)",
                        "파리 미술관 4곳 비교 (루브르/오르세/오랑주리/퐁피두)",
                        "유레일패스 vs 개별 구매",
                        "바르셀로나 가우디 투어",
                        "쉥겐 비자 & 입국 서류",
                        "프라하 1박 일정 짜기")))
                // 7개라 한 페이지(기본 10개)에 다 들어온다
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalCount").value(7))
                .andExpect(jsonPath("$.data.favoriteCount").value(2));
    }

    @Test
    @DisplayName("세션 카드에는 제목 · 시작/종료 시각(타임존 없는 ISO) · 페이지 수 · 상태 · 즐겨찾기 · 공개 · 태그가 오고 본문 · 출처는 안 온다")
    void cardFields() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[0].id").value(mock.rome().getId()))
                .andExpect(jsonPath("$.data.sessions[0].title").value("로마 3박 4일 동선 짜기"))
                .andExpect(jsonPath("$.data.sessions[0].startedAt").value("2026-04-26T21:14:00"))
                .andExpect(jsonPath("$.data.sessions[0].endedAt").value("2026-04-26T22:48:00"))
                .andExpect(jsonPath("$.data.sessions[0].pageCount").value(18))
                .andExpect(jsonPath("$.data.sessions[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.sessions[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data.sessions[0].isPublic").value(false))
                // '#' 없는 raw 태그, 저장 순서 그대로
                .andExpect(jsonPath("$.data.sessions[0].tags").value(contains("로마", "콜로세움", "바티칸", "먹거리")))
                .andExpect(jsonPath("$.data.sessions[0].summary").doesNotExist())
                .andExpect(jsonPath("$.data.sessions[0].sources").doesNotExist());
    }

    @Test
    @DisplayName("즐겨찾기만 보기(REC-10-3): favorite=true 로 부르면 서버가 걸러 로마 · 파리 두 개만 최신순으로 온다")
    void favoritesFilter() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);

        mvc.perform(get("/sessions").param("favorite", "true").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.rome(), mock.paris()))))
                .andExpect(jsonPath("$.data.sessions[*].isFavorite").value(everyItem(is(true))))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                // 상단 개수는 필터와 상관없이 전체 기준
                .andExpect(jsonPath("$.data.totalCount").value(7))
                .andExpect(jsonPath("$.data.favoriteCount").value(2));
    }

    @Test
    @DisplayName("카드에서 즐겨찾기를 켜면(REC-11) 응답과 목록에 바로 반영되고 다른 필드는 그대로다")
    void toggleFavoriteFromCard() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);

        mvc.perform(patch("/sessions/{id}", mock.flight().getId()).with(as(me)).with(json(Map.of("isFavorite", true))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isFavorite").value(true))
                .andExpect(jsonPath("$.data.title").value("유럽 항공권 비교 (인천→로마 IN, 프라하→인천 OUT)"))
                .andExpect(jsonPath("$.data.tags").value(contains("항공권", "스카이스캐너", "예산")));

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[?(@.isFavorite == true)]", hasSize(3)))
                // 즐겨찾기를 바꿔도 순서(종료 시각 최신순)는 그대로
                .andExpect(jsonPath("$.data.sessions[1].id").value(mock.flight().getId()))
                .andExpect(jsonPath("$.data.favoriteCount").value(3));
        mvc.perform(get("/sessions").param("favorite", "true").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(mock.rome(), mock.flight(), mock.paris()))));
    }

    @Test
    @DisplayName("삭제한 세션과 다른 사람 세션은 내 목록에 나오지 않는다")
    void deletedAndForeignSessionsAreHidden() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        Member other = member("wanderer");
        session(other, "동유럽 3주 살이 전체 동선", "2026-04-27T09:00:00", "2026-04-27T10:00:00", 30, false, true,
                List.of("프라하", "동유럽"), List.of(), null);

        mvc.perform(delete("/sessions/{id}", mock.rail().getId()).with(as(me))).andExpect(okEnvelope());

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data.sessions", hasSize(6)))
                .andExpect(jsonPath("$.data.sessions[*].title").value(not(hasItem("유레일패스 vs 개별 구매"))))
                .andExpect(jsonPath("$.data.sessions[*].title").value(not(hasItem("동유럽 3주 살이 전체 동선"))))
                .andExpect(jsonPath("$.data.totalCount").value(6));
    }

    @Test
    @DisplayName("세션이 하나도 없으면 sessions 빈 배열 · 개수 0 이 와서 FE 가 빈 상태를 보여준다")
    void emptyList() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalCount").value(0))
                .andExpect(jsonPath("$.data.favoriteCount").value(0));
    }

    @Test
    @DisplayName("종료 시각이 같으면 나중에 시작한 세션이 먼저 온다")
    void sameEndedAtSortsByStartedAt() throws Exception {
        Member me = member("sprout");
        LocalDateTime end = LocalDateTime.parse("2026-04-26T22:00:00");
        BrowsingSession longer = session(me, "긴 세션", end.minusHours(2), end, 3, false, false, List.of(), List.of(), null);
        BrowsingSession shorter = session(me, "짧은 세션", end.minusMinutes(10), end, 1, false, false, List.of(), List.of(), null);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(shorter, longer))));
    }

    @Test
    @DisplayName("진행 중(분석 중) 세션은 더 최근에 끝난 완료 세션보다 위에, 진행 중끼리는 시작 시각 최신순으로 온다")
    void inProgressSessionsComeFirst() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        // 시작 시각이 완료 세션들보다 오래돼도 맨 위
        BrowsingSession oldest = sessionWithStatus(me, "분석 중 (가장 먼저 시작)", "2026-04-10T09:00:00", null,
                SessionStatus.ANALYZING, List.of());
        BrowsingSession newest = sessionWithStatus(me, "분석 중 (가장 최근 시작)", "2026-04-20T09:00:00", null,
                SessionStatus.ANALYZING, List.of());
        // 진행 중이면 종료 시각이 있어도 보지 않고 시작 시각으로 줄 세운다
        BrowsingSession ended = sessionWithStatus(me, "분석 중 (종료 시각 있음)", "2026-04-15T09:00:00",
                "2026-04-30T09:00:00", SessionStatus.ANALYZING, List.of());

        List<BrowsingSession> expected = new ArrayList<>(List.of(newest, ended, oldest));
        expected.addAll(mock.newestFirst());
        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(expected.toArray(BrowsingSession[]::new)))))
                .andExpect(jsonPath("$.data.sessions[0].status").value("ANALYZING"))
                .andExpect(jsonPath("$.data.sessions[0].endedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.sessions[3].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.totalCount").value(10));
        // 페이지가 나뉘어도 같은 순서로 이어진다
        mvc.perform(get("/sessions").param("page", "1").param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(oldest, mock.rome()))));
    }

    @Test
    @DisplayName("실패 세션은 완료 세션과 섞여 종료 시각 최신순, 종료 시각이 없는 끝난 세션은 맨 뒤 (운영 Postgres 와 같은 NULL 정렬에서도)")
    void failedSessionsSortByEndedAt() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);
        // 로마(04-26 22:48 종료)와 항공권(04-25 15:21 종료) 사이
        BrowsingSession failed = sessionWithStatus(me, "분석 실패", "2026-04-25T20:00:00", "2026-04-25T21:00:00",
                SessionStatus.FAILED, List.of());
        // 가장 최근에 시작했지만 종료 시각이 없다 → endedAt desc 에서 Postgres 는 NULL 을 맨 앞에 두므로 nulls last 를 명시해야 뒤로 간다
        BrowsingSession noEndedAt = sessionWithStatus(me, "종료 시각 없는 실패", "2026-04-27T09:00:00", null,
                SessionStatus.FAILED, List.of());

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(
                        mock.rome(), failed, mock.flight(), mock.paris(), mock.rail(), mock.barca(), mock.visa(),
                        mock.prague(), noEndedAt))))
                .andExpect(jsonPath("$.data.sessions[1].status").value("FAILED"));
    }

    @Test
    @DisplayName("시작 · 종료 시각이 모두 같아도 id 역순으로 순서가 고정돼 페이지 사이에 빠지거나 겹치는 세션이 없다")
    void identicalTimestampsKeepStableOrder() throws Exception {
        Member me = member("sprout");
        LocalDateTime start = LocalDateTime.parse("2026-04-01T10:00:00");
        List<Long> savedIds = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            savedIds.add(session(me, "같은 시각 " + i, start, start.plusMinutes(30), 1, false, false,
                    List.of(), List.of(), null).getId());
        }
        List<Long> newestIdFirst = savedIds.stream().sorted(Comparator.reverseOrder()).toList();

        mvc.perform(get("/sessions").param("page", "0").param("size", "10").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id")
                        .value(contains(ids(newestIdFirst.subList(0, 10).toArray(Long[]::new)))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/sessions").param("page", "1").param("size", "10").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id")
                        .value(contains(ids(newestIdFirst.subList(10, 15).toArray(Long[]::new)))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("로그인하지 않고 목록을 열면 401")
    void requiresLogin() throws Exception {
        mvc.perform(get("/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("무한 스크롤(REC-10-1): 10개씩 종료 시각 최신순으로 받고 마지막 페이지에서 hasNext=false 로 끝을 알린다")
    void infiniteScrollPaging() throws Exception {
        Member me = member("sprout");
        seedDailySessions(me, 12);

        mvc.perform(get("/sessions").param("page", "0").param("size", "10").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(10)))
                .andExpect(jsonPath("$.data.sessions[0].title").value("세션 11"))
                .andExpect(jsonPath("$.data.sessions[9].title").value("세션 2"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(12));
        // 마지막 페이지: FE 는 hasNext=false 를 보고 '모든 기록을 확인했습니다' 를 띄운다
        mvc.perform(get("/sessions").param("page", "1").param("size", "10").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains("세션 1", "세션 0")))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalCount").value(12));
        // 끝을 넘겨 부르면 빈 페이지
        mvc.perform(get("/sessions").param("page", "2").param("size", "10").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("page · size 를 안 보내면 첫 페이지 10개가 온다")
    void defaultPageIsFirstTen() throws Exception {
        Member me = member("sprout");
        seedDailySessions(me, 12);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(10)))
                .andExpect(jsonPath("$.data.sessions[0].title").value("세션 11"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    @DisplayName("상단 개수(전체 N개 세션 · M개 즐겨찾기)는 즐겨찾기 필터 · 페이지와 상관없이 같다")
    void countsIgnoreFilterAndPage() throws Exception {
        Member me = member("sprout");
        List<BrowsingSession> daily = seedDailySessions(me, 12);
        favorite(me, daily.get(0), daily.get(3), daily.get(7), daily.get(11));
        // 삭제한 세션은 개수에서도 빠진다
        mvc.perform(delete("/sessions/{id}", daily.get(5).getId()).with(as(me))).andExpect(okEnvelope());

        List<Map<String, String>> requests = List.of(
                Map.of(),
                Map.of("page", "1", "size", "5"),
                Map.of("favorite", "true"),
                Map.of("favorite", "true", "page", "1", "size", "3"),
                Map.of("favorite", "true", "page", "9"));
        for (Map<String, String> params : requests) {
            var request = get("/sessions").with(as(me));
            params.forEach(request::param);
            mvc.perform(request)
                    .andExpect(okEnvelope())
                    .andExpect(jsonPath("$.data.totalCount").value(11))
                    .andExpect(jsonPath("$.data.favoriteCount").value(4));
        }
    }

    @Test
    @DisplayName("즐겨찾기만 보기도 페이지로 나뉜다: favorite=true&size=3 이면 즐겨찾기 4개가 3 + 1 로 온다")
    void favoriteFilterPaging() throws Exception {
        Member me = member("sprout");
        List<BrowsingSession> daily = seedDailySessions(me, 12);
        favorite(me, daily.get(0), daily.get(3), daily.get(7), daily.get(11));

        mvc.perform(get("/sessions").param("favorite", "true").param("size", "3").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains("세션 11", "세션 7", "세션 3")))
                .andExpect(jsonPath("$.data.sessions[*].isFavorite").value(everyItem(is(true))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/sessions").param("favorite", "true").param("page", "1").param("size", "3").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains("세션 0")))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        // favorite=false 는 전체
        mvc.perform(get("/sessions").param("favorite", "false").param("size", "50").with(as(me)))
                .andExpect(jsonPath("$.data.sessions", hasSize(12)));
    }

    @Test
    @DisplayName("page 가 음수 · size 가 1~50 밖이면 400 '페이지 값이 올바르지 않습니다.', 숫자가 아니면 400 '요청 형식이 올바르지 않습니다.'")
    void invalidPageParams() throws Exception {
        Member me = member("sprout");
        seedDailySessions(me, 2);

        List<Map<String, String>> outOfRange = List.of(
                Map.of("page", "-1"),
                Map.of("size", "0"),
                Map.of("size", "-5"),
                Map.of("size", "51"),
                // 건너뛸 개수(page * size)가 int 를 넘는 경우
                Map.of("page", String.valueOf(Integer.MAX_VALUE), "size", "50"));
        for (Map<String, String> params : outOfRange) {
            var request = get("/sessions").with(as(me));
            params.forEach(request::param);
            mvc.perform(request).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_PAGE));
        }

        List<Map<String, String>> notNumbers = List.of(
                Map.of("page", "abc"),
                Map.of("size", "ten"),
                Map.of("page", "1.5"),
                Map.of("page", "99999999999"),
                Map.of("favorite", "maybe"));
        for (Map<String, String> params : notNumbers) {
            var request = get("/sessions").with(as(me));
            params.forEach(request::param);
            mvc.perform(request).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID_REQUEST));
        }

        // 경계값은 통과
        mvc.perform(get("/sessions").param("size", "1").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(1)))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/sessions").param("size", "50").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(2)));
    }

    /** 2026-04-01 부터 하루에 하나씩, 30분짜리 완료 세션 ("세션 0" 이 가장 오래됨). */
    private List<BrowsingSession> seedDailySessions(Member owner, int count) {
        LocalDateTime base = LocalDateTime.parse("2026-04-01T10:00:00");
        List<BrowsingSession> seeded = new ArrayList<>();
        for (int day = 0; day < count; day++) {
            seeded.add(session(owner, "세션 " + day, base.plusDays(day), base.plusDays(day).plusMinutes(30), 2,
                    false, false, List.of(), List.of(), null));
        }
        return seeded;
    }

    /** 카드에서 즐겨찾기를 켠다 (PATCH /sessions/{id}). */
    private void favorite(Member owner, BrowsingSession... targets) throws Exception {
        for (BrowsingSession target : targets) {
            mvc.perform(patch("/sessions/{id}", target.getId()).with(as(owner)).with(json(Map.of("isFavorite", true))))
                    .andExpect(okEnvelope());
        }
    }
}
