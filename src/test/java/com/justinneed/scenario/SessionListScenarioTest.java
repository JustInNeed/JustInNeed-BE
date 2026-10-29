package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.BrowsingSession;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 세션 목록 화면 (/sessions). FE: src/features/sessions/SessionsView.tsx, SessionCard.tsx.
 * 시드: FE 목 MOCK_SESSIONS 7개 (로마 3박 4일 동선 짜기 ~ 프라하 1박 일정 짜기).
 */
@DisplayName("세션 목록 화면")
class SessionListScenarioTest extends ScenarioTestSupport {

    @Test
    @DisplayName("세션 목록은 종료 시각 최신순(REC-10-1)으로 온다 — 저장 순서와 상관없다")
    void listIsNewestFirst() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data", hasSize(7)))
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(mock.newestFirst().toArray(BrowsingSession[]::new)))))
                .andExpect(jsonPath("$.data[*].title").value(contains(
                        "로마 3박 4일 동선 짜기",
                        "유럽 항공권 비교 (인천→로마 IN, 프라하→인천 OUT)",
                        "파리 미술관 4곳 비교 (루브르/오르세/오랑주리/퐁피두)",
                        "유레일패스 vs 개별 구매",
                        "바르셀로나 가우디 투어",
                        "쉥겐 비자 & 입국 서류",
                        "프라하 1박 일정 짜기")));
    }

    @Test
    @DisplayName("세션 카드에는 제목 · 시작/종료 시각(타임존 없는 ISO) · 페이지 수 · 상태 · 즐겨찾기 · 공개 · 태그가 오고 본문 · 출처는 안 온다")
    void cardFields() throws Exception {
        Member me = member("sprout");
        MockSessions mock = seedMockSessions(me);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data[0].id").value(mock.rome().getId()))
                .andExpect(jsonPath("$.data[0].title").value("로마 3박 4일 동선 짜기"))
                .andExpect(jsonPath("$.data[0].startedAt").value("2026-04-26T21:14:00"))
                .andExpect(jsonPath("$.data[0].endedAt").value("2026-04-26T22:48:00"))
                .andExpect(jsonPath("$.data[0].pageCount").value(18))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data[0].isFavorite").value(true))
                .andExpect(jsonPath("$.data[0].isPublic").value(false))
                // '#' 없는 raw 태그, 저장 순서 그대로
                .andExpect(jsonPath("$.data[0].tags").value(contains("로마", "콜로세움", "바티칸", "먹거리")))
                .andExpect(jsonPath("$.data[0].summary").doesNotExist())
                .andExpect(jsonPath("$.data[0].sources").doesNotExist());
    }

    @Test
    @DisplayName("즐겨찾기만 보기(REC-10-3): FE 가 isFavorite 로 거르면 로마 · 파리 두 개만 남는다")
    void favoritesFilter() throws Exception {
        Member me = member("sprout");
        seedMockSessions(me);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data[?(@.isFavorite == true)].title").value(containsInAnyOrder(
                        "로마 3박 4일 동선 짜기", "파리 미술관 4곳 비교 (루브르/오르세/오랑주리/퐁피두)")));
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
                .andExpect(jsonPath("$.data[?(@.isFavorite == true)]", hasSize(3)))
                // 즐겨찾기를 바꿔도 순서(종료 시각 최신순)는 그대로
                .andExpect(jsonPath("$.data[1].id").value(mock.flight().getId()));
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
                .andExpect(jsonPath("$.data", hasSize(6)))
                .andExpect(jsonPath("$.data[*].title").value(not(hasItem("유레일패스 vs 개별 구매"))))
                .andExpect(jsonPath("$.data[*].title").value(not(hasItem("동유럽 3주 살이 전체 동선"))));
    }

    @Test
    @DisplayName("세션이 하나도 없으면 빈 배열이 와서 FE 가 빈 상태를 보여준다")
    void emptyList() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("종료 시각이 같으면 나중에 시작한 세션이 먼저 온다")
    void sameEndedAtSortsByStartedAt() throws Exception {
        Member me = member("sprout");
        LocalDateTime end = LocalDateTime.parse("2026-04-26T22:00:00");
        BrowsingSession longer = session(me, "긴 세션", end.minusHours(2), end, 3, false, false, List.of(), List.of(), null);
        BrowsingSession shorter = session(me, "짧은 세션", end.minusMinutes(10), end, 1, false, false, List.of(), List.of(), null);

        mvc.perform(get("/sessions").with(as(me)))
                .andExpect(jsonPath("$.data[*].id").value(contains(ids(shorter, longer))));
    }

    @Test
    @DisplayName("로그인하지 않고 목록을 열면 401")
    void requiresLogin() throws Exception {
        mvc.perform(get("/sessions")).andExpect(status().isUnauthorized());
    }

    @Test
    @Disabled("API 미구현: GET /sessions?page=&size= 무한 스크롤(10개씩) + 끝 표시(REC-10-1, '모든 기록을 확인했습니다') — "
            + "응답 모양 data.sessions · data.hasNext 는 제안, 구현되면 켠다")
    @DisplayName("무한 스크롤: 10개씩 최신순으로 받고 마지막 페이지에서 hasNext=false 로 끝을 알린다")
    void infiniteScrollPaging() throws Exception {
        Member me = member("sprout");
        LocalDateTime base = LocalDateTime.parse("2026-04-01T10:00:00");
        for (int day = 0; day < 12; day++) {
            session(me, "세션 " + day, base.plusDays(day), base.plusDays(day).plusMinutes(30), 2, false, false,
                    List.of(), List.of(), null);
        }

        mvc.perform(get("/sessions").param("page", "0").param("size", "10").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(10)))
                .andExpect(jsonPath("$.data.sessions[0].title").value("세션 11"))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/sessions").param("page", "1").param("size", "10").with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].title").value(contains("세션 1", "세션 0")))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }
}
