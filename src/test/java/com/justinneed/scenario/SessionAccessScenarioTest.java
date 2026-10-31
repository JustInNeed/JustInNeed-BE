package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

/**
 * GET /sessions/{id} 접근 규칙. 없거나 삭제 404, 소유자는 전부, 다른 사용자는 공개 세션만 읽기 전용, 그 밖에는 403.
 * "공개"는 커뮤니티가 보여주는 기준(CommunitySharing.visibleSessions)과 같다.
 */
@DisplayName("세션 상세 접근 규칙")
class SessionAccessScenarioTest extends ScenarioTestSupport {

    private static final String NOT_FOUND = "세션을 찾을 수 없습니다.";
    private static final String FORBIDDEN = "비공개 세션이라 볼 수 없어요.";

    private Member me;
    private Wanderer wanderer;

    @BeforeEach
    void seed() {
        me = member("sprout");
        wanderer = seedWanderer();
    }

    private void showContent(Wanderer owner) {
        CommunityProfile profile = communityProfiles.findById(owner.id()).orElseThrow();
        profile.changeVisibility(NodeVisibility.CONTENT);
        communityProfiles.saveAndFlush(profile);
    }

    @ParameterizedTest
    @EnumSource(SessionStatus.class)
    @DisplayName("소유자는 어떤 상태든 전체를 보고 isOwner=true 다")
    void ownerSeesEveryStatus(SessionStatus status) throws Exception {
        BrowsingSession mine = sessionWithStatus(me, "내 세션", "2026-10-30T09:00:00", null, status, List.of("로마"));

        mvc.perform(get("/sessions/{id}", mine.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isOwner").value(true))
                .andExpect(jsonPath("$.data.title").value("내 세션"))
                .andExpect(jsonPath("$.data.status").value(status.name()));
    }

    @Test
    @DisplayName("PATCH 응답도 소유자라 isOwner=true 다")
    void patchResponseIsOwner() throws Exception {
        BrowsingSession rome = seedMockSessions(me).rome();

        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(Map.of("isFavorite", false))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isOwner").value(true));
    }

    @Test
    @DisplayName("다른 사용자는 공개 세션을 읽기 전용으로 본다: isOwner=false, 소유자의 즐겨찾기는 보이지 않는다")
    void foreignPublicSessionIsReadOnly() throws Exception {
        showContent(wanderer);
        wanderer.prague().update(null, null, null, true, null);
        sessions.saveAndFlush(wanderer.prague());

        mvc.perform(get("/sessions/{id}", wanderer.prague().getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isOwner").value(false))
                .andExpect(jsonPath("$.data.isFavorite").value(false))
                .andExpect(jsonPath("$.data.isPublic").value(true))
                .andExpect(jsonPath("$.data.title").value("프라하 1박 일정 짜기"))
                .andExpect(jsonPath("$.data.tags").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.summary.markdown").value("# 프라하 1박\n\n카를교 → 프라하 성 순서로 걷는다."))
                .andExpect(jsonPath("$.data.sources[0].excerpt").value("성 내부는 통합권으로 본다."));
    }

    @Test
    @DisplayName("작성자의 노드 공개 범위가 URL 만이면 커뮤니티 API 처럼 제목 · 본문 · 발췌 없이 링크만 보인다")
    void urlOnlyProfileHidesContent() throws Exception {
        mvc.perform(get("/sessions/{id}", wanderer.prague().getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isOwner").value(false))
                .andExpect(jsonPath("$.data.title").value(""))
                .andExpect(jsonPath("$.data.summary").value(nullValue()))
                .andExpect(jsonPath("$.data.tags").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.sources", hasSize(2)))
                .andExpect(jsonPath("$.data.sources[0].url").value("https://www.hrad.cz/en/prague-castle-for-visitors"))
                .andExpect(jsonPath("$.data.sources[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.sources[0].excerpt").value(nullValue()));
    }

    @Test
    @DisplayName("소유자는 공개 범위와 상관없이 전체를 본다")
    void ownerIgnoresUrlOnly() throws Exception {
        mvc.perform(get("/sessions/{id}", wanderer.prague().getId()).with(as(wanderer.member())))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isOwner").value(true))
                .andExpect(jsonPath("$.data.title").value("프라하 1박 일정 짜기"));
    }

    @Test
    @DisplayName("다른 사용자에게 비공개 세션은 403 '비공개 세션이라 볼 수 없어요.'")
    void foreignPrivateSession() throws Exception {
        mvc.perform(get("/sessions/{id}", wanderer.slow().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.FORBIDDEN, FORBIDDEN));
    }

    @Test
    @DisplayName("공개 세션이어도 비공개 해시태그가 섞여 있으면 커뮤니티처럼 다른 사용자에겐 403 이고, 소유자는 그대로 본다")
    void hiddenTagSession() throws Exception {
        mvc.perform(get("/sessions/{id}", wanderer.cafe().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.FORBIDDEN, FORBIDDEN));
        mvc.perform(get("/sessions/{id}", wanderer.cafe().getId()).with(as(wanderer.member())))
                .andExpect(okEnvelope());
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("is_public 이 켜져 있어도 분석이 끝나지 않은 세션은 다른 사용자에게 403 이다")
    void unfinishedPublicSession(SessionStatus status) throws Exception {
        BrowsingSession unfinished = sessionWithStatus(wanderer.member(), "진행 중", "2026-10-30T09:00:00", null,
                status, List.of("프라하"));
        unfinished.update(null, null, true, null, null);
        sessions.saveAndFlush(unfinished);

        mvc.perform(get("/sessions/{id}", unfinished.getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.FORBIDDEN, FORBIDDEN));
    }

    @Test
    @DisplayName("탈퇴한 회원의 공개 세션은 다른 사용자에게 403 이다")
    void withdrawnOwner() throws Exception {
        wanderer.member().withdraw(null, LocalDateTime.of(2026, 10, 30, 9, 0));
        members.saveAndFlush(wanderer.member());

        mvc.perform(get("/sessions/{id}", wanderer.prague().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.FORBIDDEN, FORBIDDEN));
    }

    @Test
    @DisplayName("지운 세션은 소유자 · 다른 사용자 모두 404 다 (공개였어도)")
    void deletedSessionIs404ForEveryone() throws Exception {
        wanderer.prague().delete();
        sessions.saveAndFlush(wanderer.prague());

        mvc.perform(get("/sessions/{id}", wanderer.prague().getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, NOT_FOUND));
        mvc.perform(get("/sessions/{id}", wanderer.prague().getId()).with(as(wanderer.member())))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, NOT_FOUND));
    }

    @Test
    @DisplayName("다른 사용자는 공개 세션이어도 수정 · 삭제할 수 없다 (404)")
    void foreignPublicSessionCannotBeChanged() throws Exception {
        showContent(wanderer);
        Long id = wanderer.prague().getId();

        mvc.perform(patch("/sessions/{id}", id).with(as(me)).with(json(Map.of("isFavorite", true))))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, NOT_FOUND));
        mvc.perform(delete("/sessions/{id}", id).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, NOT_FOUND));
    }
}
