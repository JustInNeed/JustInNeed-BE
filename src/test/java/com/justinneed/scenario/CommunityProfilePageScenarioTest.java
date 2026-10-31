package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 다른 사용자 프로필 화면 (/profile/{id}) 과 카드 상세의 관련 세션.
 * FE: ProfileView.tsx (isMine=false), MindmapDetailModal 의 sessions. 시드는 ScenarioTestSupport#seedWanderer.
 */
@DisplayName("프로필 화면 · 관련 세션")
class CommunityProfilePageScenarioTest extends ScenarioTestSupport {

    private static final String SESSION_NOT_FOUND = "세션을 찾을 수 없습니다.";
    private static final String MEMBER_NOT_FOUND = "회원을 찾을 수 없습니다.";
    private static final String RELATED = "/community/mindmaps/{id}/sessions";
    private static final String MINDMAPS = "/community/profiles/{id}/mindmaps";
    private static final Source LINK = new Source("글", "https://prague.example.com/a", "발췌");

    @Autowired private SessionResultRepository results;
    @Autowired private JdbcTemplate jdbc;

    private Member me;
    private Wanderer wanderer;

    @BeforeEach
    void seed() {
        me = member("sprout");
        wanderer = seedWanderer();
    }

    private BrowsingSession publicSession(Member owner, String title, String startedAt, String endedAt, boolean favorite,
            List<String> tags) {
        return session(owner, title, startedAt, endedAt, 3, favorite, true, tags, List.of(LINK), "# 본문");
    }

    private void showContent() throws Exception {
        mvc.perform(patch("/community/me/visibility").with(as(wanderer.member())).with(json(Map.of("visibility", "CONTENT"))))
                .andExpect(okEnvelope());
    }

    private void withdraw(Member member) {
        members.findById(member.getId()).orElseThrow().withdraw(null, LocalDateTime.now());
        members.flush();
    }

    // ---------- GET /community/mindmaps/{id}/sessions ----------

    @Test
    @DisplayName("관련 세션: 그 세션이 먼저, 이어서 공개 태그가 겹치는 같은 작성자의 공개 세션이 최신순으로, 최대 3개 온다")
    void relatedSessionsOrder() throws Exception {
        BrowsingSession newest = publicSession(wanderer.member(), "새 글", "2026-04-22T10:00:00", "2026-04-22T11:00:00",
                false, List.of("프라하"));

        mvc.perform(get(RELATED, wanderer.nightjet().getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(
                        ids(wanderer.nightjet(), newest, wanderer.prague()))));
        mvc.perform(get(RELATED, wanderer.prague().getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions", hasSize(3)))
                .andExpect(jsonPath("$.data.sessions[0].id").value(wanderer.prague().getId()))
                .andExpect(jsonPath("$.data.sessions[1].id").value(newest.getId()));
    }

    @Test
    @DisplayName("관련 세션의 최신순은 끝난 시각 기준이다: 늦게 시작했어도 먼저 끝난 세션이 뒤로 간다")
    void relatedSessionsOrderByEndTime() throws Exception {
        BrowsingSession longRun = publicSession(wanderer.member(), "오래 본 글", "2026-04-21T08:00:00",
                "2026-04-21T12:00:00", false, List.of("체코"));
        BrowsingSession shortRun = publicSession(wanderer.member(), "잠깐 본 글", "2026-04-21T09:00:00",
                "2026-04-21T09:10:00", false, List.of("체코"));

        mvc.perform(get(RELATED, wanderer.prague().getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(wanderer.prague(), longRun, shortRun))));
    }

    @Test
    @DisplayName("관련 세션 항목은 세션 목록 항목과 같은 필드다. URL_ONLY 면 제목은 빈 문자열, 즐겨찾기는 작성자 값과 상관없이 false")
    void relatedSessionFields() throws Exception {
        BrowsingSession favorite = publicSession(wanderer.member(), "즐겨찾기한 글", "2026-04-22T10:00:00",
                "2026-04-22T11:00:00", true, List.of("비엔나", "카페"));

        mvc.perform(get(RELATED, favorite.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[0].id").value(favorite.getId()))
                .andExpect(jsonPath("$.data.sessions[0].title").value(""))
                .andExpect(jsonPath("$.data.sessions[0].startedAt").value("2026-04-22T10:00:00"))
                .andExpect(jsonPath("$.data.sessions[0].endedAt").value("2026-04-22T11:00:00"))
                .andExpect(jsonPath("$.data.sessions[0].pageCount").value(3))
                .andExpect(jsonPath("$.data.sessions[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.sessions[0].isFavorite").value(false))
                .andExpect(jsonPath("$.data.sessions[0].isPublic").value(true))
                .andExpect(jsonPath("$.data.sessions[0].tags").value(contains("비엔나")));

        showContent();

        mvc.perform(get(RELATED, favorite.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[0].title").value("즐겨찾기한 글"));
    }

    @Test
    @DisplayName("작성자 본인이 불러도 방문자 규칙이다 (카드 응답과 같다)")
    void ownerGetsVisitorView() throws Exception {
        mvc.perform(get(RELATED, wanderer.cafe().getId()).with(as(wanderer.member())))
                .andExpect(jsonPath("$.data.sessions[0].title").value(""))
                .andExpect(jsonPath("$.data.sessions[0].tags").value(contains("비엔나")));
    }

    @Test
    @DisplayName("비공개 해시태그로만 겹치는 세션은 관련 세션이 아니고, 대소문자가 달라도 같은 태그다")
    void relatedSessionsUsePublicTagsOnly() throws Exception {
        BrowsingSession onlyHidden = publicSession(wanderer.member(), "카페만", "2026-04-22T10:00:00",
                "2026-04-22T11:00:00", false, List.of("카페"));
        BrowsingSession upper = publicSession(wanderer.member(), "대문자", "2026-04-23T10:00:00", "2026-04-23T11:00:00",
                false, List.of("ROME"));
        BrowsingSession lower = publicSession(wanderer.member(), "소문자", "2026-04-22T10:00:00", "2026-04-22T11:00:00",
                false, List.of("rome"));

        mvc.perform(get(RELATED, onlyHidden.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(onlyHidden))))
                .andExpect(jsonPath("$.data.sessions[0].tags", empty()));
        mvc.perform(get(RELATED, lower.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(lower, upper))));
    }

    @Test
    @DisplayName("다른 사람의 세션 · 비공개 세션 · 태그가 안 겹치는 세션은 관련 세션이 아니다")
    void relatedSessionsStayWithinOwnersPublicSessions() throws Exception {
        Member other = member("foodtrip");
        publicSession(other, "남의 글", "2026-04-22T10:00:00", "2026-04-22T11:00:00", false, List.of("프라하"));
        session(wanderer.member(), "비공개 글", "2026-04-22T10:00:00", "2026-04-22T11:00:00", 1, false, false,
                List.of("프라하"), List.of(LINK), "# 본문");

        mvc.perform(get(RELATED, wanderer.prague().getId()).with(as(me)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(wanderer.prague(), wanderer.nightjet()))));
    }

    @Test
    @DisplayName("없거나 공개 카드가 아닌 세션 · 탈퇴한 작성자의 카드는 404")
    void relatedSessionsOfMissingCardAre404() throws Exception {
        BrowsingSession inProgress = sessionWithStatus(wanderer.member(), "분석 중", "2026-04-22T10:00:00",
                null, SessionStatus.ANALYZING, List.of("프라하"));

        mvc.perform(get(RELATED, 999_999L).with(as(me))).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        mvc.perform(get(RELATED, wanderer.slow().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
        mvc.perform(get(RELATED, inProgress.getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));

        withdraw(wanderer.member());

        mvc.perform(get(RELATED, wanderer.prague().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
    }

    @Test
    @DisplayName("비공개로 바꾼 세션의 관련 세션은 404")
    void relatedSessionsFollowPublicRule() throws Exception {
        mvc.perform(patch("/sessions/{id}", wanderer.vienna().getId()).with(as(wanderer.member()))
                .with(json(Map.of("isPublic", false)))).andExpect(okEnvelope());

        mvc.perform(get(RELATED, wanderer.vienna().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, SESSION_NOT_FOUND));
    }

    // ---------- GET /community/profiles/{id}/mindmaps ----------

    @Test
    @DisplayName("프로필 마인드맵 탭: 공개 카드가 최신순으로, 비공개 세션은 빼고 온다. URL_ONLY 면 제목 · 요약은 null")
    void profileMindmaps() throws Exception {
        mvc.perform(get(MINDMAPS, wanderer.id()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(
                        ids(wanderer.prague(), wanderer.nightjet(), wanderer.vienna(), wanderer.cafe()))))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.userId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].snippet").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(2))
                .andExpect(jsonPath("$.data.mindmaps[3].hashtags").value(contains("비엔나")));
    }

    @Test
    @DisplayName("프로필 마인드맵 탭 페이지: hasNext 로 다음 페이지를 알리고, 같은 시각이면 나중 세션이 먼저다")
    void profileMindmapsPaging() throws Exception {
        BrowsingSession tie = publicSession(wanderer.member(), "같은 시각", "2026-04-20T23:05:00", "2026-04-20T23:32:00",
                false, List.of("체코"));

        mvc.perform(get(MINDMAPS, wanderer.id()).param("page", "0").param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(tie, wanderer.prague()))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(MINDMAPS, wanderer.id()).param("page", "2").param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(wanderer.cafe()))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("페이지 값이 범위를 벗어나면 400, 없거나 탈퇴한 회원의 마인드맵 탭은 404")
    void profileMindmapsErrors() throws Exception {
        mvc.perform(get(MINDMAPS, wanderer.id()).param("page", "-1").with(as(me))).andExpect(status().isBadRequest());
        mvc.perform(get(MINDMAPS, wanderer.id()).param("size", "0").with(as(me))).andExpect(status().isBadRequest());
        mvc.perform(get(MINDMAPS, 999_999L).with(as(me))).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));

        withdraw(wanderer.member());

        mvc.perform(get(MINDMAPS, wanderer.id()).with(as(me))).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
    }

    // ---------- GET /community/profiles/{id} ----------

    @Test
    @DisplayName("프로필 헤더: 소개 · 공개 범위 · 팔로우 상태 · 나도 탐색중 수가 함께 온다")
    void profileHeaderFields() throws Exception {
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bio").value(nullValue()))
                .andExpect(jsonPath("$.data.visibility").value("URL_ONLY"))
                .andExpect(jsonPath("$.data.following").value(false))
                .andExpect(jsonPath("$.data.exploringCount").value(0));

        mvc.perform(patch("/community/me/profile").with(as(wanderer.member())).with(json(Map.of("bio", "느리게 걷는 사람"))))
                .andExpect(okEnvelope());
        mvc.perform(patch("/community/profiles/{id}/follow", wanderer.id()).with(as(me)).with(json(Map.of("following", true))))
                .andExpect(okEnvelope());
        showContent();

        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.bio").value("느리게 걷는 사람"))
                .andExpect(jsonPath("$.data.visibility").value("CONTENT"))
                .andExpect(jsonPath("$.data.following").value(true));
        // 팔로우는 나만의 상태다
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(member("foodtrip"))))
                .andExpect(jsonPath("$.data.following").value(false));
    }

    @Test
    @DisplayName("노드 수는 카드 nodeCount 의 합이다: 같은 URL 이 두 세션에 있으면 카드마다 세고, CONTENT 면 URL 이 아니라 AI 노드를 센다")
    void totalNodeCountIsSumOfCards() throws Exception {
        Member traveler = member("traveler");
        BrowsingSession first = session(traveler, "첫 세션", "2026-04-20T10:00:00", "2026-04-20T11:00:00", 2, false, true,
                List.of("여행"), List.of(LINK, new Source("둘째", "https://prague.example.com/b", "발췌")), "# 본문");
        session(traveler, "둘째 세션", "2026-04-21T10:00:00", "2026-04-21T11:00:00", 1, false, true,
                List.of("여행"), List.of(LINK), "# 본문");

        mvc.perform(get("/community/profiles/{id}", traveler.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.totalNodeCount").value(3));

        saveMindmap(first, 5);
        cardSync.sync(first);
        mvc.perform(patch("/community/me/visibility").with(as(traveler)).with(json(Map.of("visibility", "CONTENT"))))
                .andExpect(okEnvelope());

        mvc.perform(get(MINDMAPS, traveler.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].nodeCount").value(contains(0, 5)));
        mvc.perform(get("/community/profiles/{id}", traveler.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.totalNodeCount").value(5));
    }

    // 뿌리 하나 + 자식 size-1 개짜리 AI 마인드맵
    private void saveMindmap(BrowsingSession session, int size) {
        List<String> nodes = new ArrayList<>();
        nodes.add("{\"id\": \"root\", \"type\": \"root\", \"keyword\": \"뿌리\", \"score\": 1.0, \"childCount\": 0}");
        for (int i = 1; i < size; i++) {
            nodes.add("{\"id\": \"topic:%d\", \"type\": \"topic\", \"keyword\": \"주제%d\", \"score\": 0.5, \"childCount\": 0}"
                    .formatted(i, i));
        }
        String mindmap = "{\"structureSource\": \"llm\", \"nodes\": [%s], \"edges\": []}"
                .formatted(String.join(",", nodes));
        results.saveAndFlush(new SessionResult(session.getId(), session.getUserId()));
        jdbc.update("update session_results set mindmap = JSON '" + mindmap + "', selected_units = JSON '[]'"
                + " where session_id = ?", session.getId());
    }

    @Test
    @DisplayName("닉네임을 정하지 않은 사람의 프로필 · 전체 마인드맵: nickname 은 null, 제목은 닉네임 없이 온다")
    void profileWithoutNickname() throws Exception {
        Member unnamed = member(null, SocialProvider.KAKAO, null);

        mvc.perform(get("/community/profiles/{id}", unnamed.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nickname").value(nullValue()));
        mvc.perform(get("/community/profiles/{id}/mindmap", unnamed.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.nickname").value(nullValue()))
                .andExpect(jsonPath("$.data.title").value("전체 마인드맵"));
    }

    @Test
    @DisplayName("탈퇴한 회원의 프로필 · 전체 마인드맵 · 공유 세션은 404")
    void withdrawnProfileIs404() throws Exception {
        withdraw(wanderer.member());

        for (String path : List.of("/community/profiles/{id}", "/community/profiles/{id}/mindmap",
                "/community/profiles/{id}/shared-sessions")) {
            mvc.perform(get(path, wanderer.id()).with(as(me))).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MEMBER_NOT_FOUND));
        }
    }

    @Test
    @DisplayName("소개 수정: 앞뒤 공백은 지우고 120자까지 저장한다. 빼먹으면 400, 탈퇴하면 소개도 지운다")
    void bioRules() throws Exception {
        String bio = "가".repeat(120);

        mvc.perform(patch("/community/me/profile").with(as(wanderer.member())).with(json(Map.of("bio", "  " + "소개  "))))
                .andExpect(jsonPath("$.data.bio").value("소개"));
        mvc.perform(patch("/community/me/profile").with(as(wanderer.member())).with(json(Map.of("bio", bio))))
                .andExpect(okEnvelope());
        mvc.perform(patch("/community/me/profile").with(as(wanderer.member())).with(json("{}")))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "소개를 입력해 주세요."));
        assertThat(communityProfiles.findById(wanderer.id()).orElseThrow().getBio()).isEqualTo(bio);

        mvc.perform(delete("/settings/account").with(as(wanderer.member())).with(json(Map.of("confirmText", "WITHDRAW"))))
                .andExpect(okEnvelope());

        assertThat(communityProfiles.findById(wanderer.id()).orElseThrow().getBio()).isNull();
    }

    @Test
    @DisplayName("로그인하지 않으면 관련 세션 · 프로필 마인드맵 탭은 401")
    void loginRequired() throws Exception {
        mvc.perform(get(RELATED, wanderer.prague().getId())).andExpect(status().isUnauthorized());
        mvc.perform(get(MINDMAPS, wanderer.id())).andExpect(status().isUnauthorized());
    }

    // ---------- 공유 세션 · 전체 마인드맵 ----------

    @Test
    @DisplayName("공유 세션 카드 필드: URL_ONLY 에서도 시각 · 페이지 수 · 상태가 오고 제목은 빠진다. 프로필의 pinnedSessions 도 같다")
    void sharedSessionCardFieldsUnderUrlOnly() throws Exception {
        mvc.perform(get("/community/profiles/{id}/shared-sessions", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data[0].title").doesNotExist())
                .andExpect(jsonPath("$.data[0].startedAt").value("2026-04-20T23:05:00"))
                .andExpect(jsonPath("$.data[0].endedAt").value("2026-04-20T23:32:00"))
                .andExpect(jsonPath("$.data[0].pageCount").value(6))
                .andExpect(jsonPath("$.data[0].status").value("COMPLETED"));
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", wanderer.id(), wanderer.prague().getId())
                        .with(as(me)))
                .andExpect(jsonPath("$.data.startedAt").value("2026-04-20T23:05:00"))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        mvc.perform(get("/community/profiles/{id}", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.pinnedSessions[0].pageCount").value(6));
    }

    @Test
    @DisplayName("전체 마인드맵 제목은 작성자 닉네임으로 된 한국어다")
    void fullMindmapTitle() throws Exception {
        mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).with(as(me)))
                .andExpect(jsonPath("$.data.title").value("wanderer님의 전체 마인드맵"));
    }
}
