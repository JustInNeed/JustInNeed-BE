package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 다른 사람에게 보이는 프로필의 공개 세션 순서. 세션 탭과 마인드맵 탭이 같은 순서를 쓴다:
 * 고정(살아 있는 것만) 고정 순서 → 나머지는 마지막 활동(종료, 없으면 시작) 최신순 → 같으면 id 역순.
 */
@DisplayName("프로필 공개 세션 순서")
class PublicSessionOrderScenarioTest extends ScenarioTestSupport {

    private static final String SESSIONS = "/community/profiles/{id}/shared-sessions";
    private static final String MINDMAPS = "/community/profiles/{id}/mindmaps";

    private Member owner;
    private Member viewer;
    private BrowsingSession older;
    private BrowsingSession middle;
    private BrowsingSession newest;
    private BrowsingSession tie;
    private BrowsingSession hidden;

    @BeforeEach
    void seed() {
        owner = member("sprout");
        viewer = member("foodtrip");
        older = publicSession("오래된", "2026-04-01T09:00:00");
        middle = publicSession("중간", "2026-04-10T09:00:00");
        newest = publicSession("최신", "2026-04-20T09:00:00");
        tie = publicSession("같은 시각", "2026-04-10T09:00:00");
        hidden = session(owner, "비공개", "2026-04-25T09:00:00", "2026-04-25T10:00:00", 1, false, false,
                List.of("로마"), sources(), "본문");
    }

    private BrowsingSession publicSession(String title, String startedAt) {
        return session(owner, title, startedAt, startedAt.replace("T09", "T10"), 1, false, true,
                List.of("로마"), sources(), "본문");
    }

    private static List<Source> sources() {
        return List.of(new Source("글", "https://example.com/a", "발췌"));
    }

    private void pin(BrowsingSession session) throws Exception {
        mvc.perform(post("/community/me/pinned-sessions").with(as(owner)).with(json(Map.of("sessionId", session.getId()))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("고정이 없으면 최신 활동순이고, 같은 시각이면 나중 세션이 먼저다. 비공개는 빠진다")
    void newestFirstWithoutPins() throws Exception {
        mvc.perform(get(SESSIONS, owner.getId()).with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(newest, tie, middle, older))))
                .andExpect(jsonPath("$.data.sessions[*].isPinned").value(contains(false, false, false, false)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("종료 시각이 없는 공개 세션은 시작 시각으로 줄을 선다")
    void lastActivityFallsBackToStart() throws Exception {
        BrowsingSession open = session(owner, "종료 없음", "2026-04-15T09:00:00", "2026-04-15T10:00:00", 1, false, true,
                List.of("로마"), sources(), "본문");
        ReflectionTestUtils.setField(open, "endedAt", null);
        sessions.saveAndFlush(open);

        mvc.perform(get(SESSIONS, owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(newest, open, tie, middle, older))));
    }

    @Test
    @DisplayName("세션 탭: 고정이 고정 순서로 먼저 오고, 고정 안 한 공개 세션이 그 뒤에 isPinned=false 로 온다")
    void pinnedFirstThenPublic() throws Exception {
        pin(older);
        pin(middle);

        mvc.perform(get(SESSIONS, owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(older, middle, newest, tie))))
                .andExpect(jsonPath("$.data.sessions[*].isPinned").value(contains(true, true, false, false)));
    }

    @Test
    @DisplayName("세션 탭 페이지: 고정이 페이지 경계를 넘어도 빠지거나 겹치지 않고, hasNext 가 맞다")
    void sessionPaging() throws Exception {
        pin(tie);
        pin(older);

        mvc.perform(get(SESSIONS, owner.getId()).param("page", "0").param("size", "1").with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(tie))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(SESSIONS, owner.getId()).param("page", "1").param("size", "1").with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(older))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(SESSIONS, owner.getId()).param("page", "0").param("size", "2").with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(tie, older))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(SESSIONS, owner.getId()).param("page", "1").param("size", "2").with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(newest, middle))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(SESSIONS, owner.getId()).param("page", "2").param("size", "2").with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions").isEmpty())
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("세션 탭 기본 크기는 12 이고, 범위를 벗어난 페이지 값은 400")
    void defaultSizeAndErrors() throws Exception {
        for (int i = 0; i < 9; i++) {
            publicSession("추가" + i, "2026-03-1" + i + "T09:00:00");
        }

        mvc.perform(get(SESSIONS, owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions.length()").value(12))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(SESSIONS, owner.getId()).param("page", "1").with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(SESSIONS, owner.getId()).param("page", "-1").with(as(viewer))).andExpect(status().isBadRequest());
        mvc.perform(get(SESSIONS, owner.getId()).param("size", "101").with(as(viewer))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("마인드맵 탭도 같은 순서고, 프로필 카드에만 isPinned 가 있다")
    void cardsFollowTheSameOrder() throws Exception {
        pin(older);
        pin(middle);

        mvc.perform(get(MINDMAPS, owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(older, middle, newest, tie))))
                .andExpect(jsonPath("$.data.mindmaps[*].isPinned").value(contains(true, true, false, false)))
                .andExpect(jsonPath("$.data.mindmaps[0].card").doesNotExist())
                .andExpect(jsonPath("$.data.mindmaps[0].owner.nickname").value("sprout"));
        mvc.perform(get(MINDMAPS, owner.getId()).param("page", "1").param("size", "3").with(as(viewer)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(tie))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get("/community/search/mindmaps").param("q", "로마").with(as(viewer)))
                .andExpect(jsonPath("$.data.mindmaps[0].mindmapId").isNumber())
                .andExpect(jsonPath("$.data.mindmaps[0].isPinned").doesNotExist());
    }

    @Test
    @DisplayName("지운 세션의 고정은 순서 · 개수에 끼지 않고, 공개 세션 수는 고정과 상관없다")
    void deletedPinAndCount() throws Exception {
        pin(older);
        pin(middle);
        mvc.perform(delete("/sessions/{id}", older.getId()).with(as(owner))).andExpect(okEnvelope());

        mvc.perform(get(SESSIONS, owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(ids(middle, newest, tie))));
        mvc.perform(get(MINDMAPS, owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.mindmaps[*].mindmapId").value(contains(ids(middle, newest, tie))));
        mvc.perform(get("/community/profiles/{id}", owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sharedSessionCount").value(3))
                .andExpect(jsonPath("$.data.pinnedSessions[*].id").value(contains(ids(middle))));

        mvc.perform(patch("/sessions/{id}", hidden.getId()).with(as(owner)).with(json(Map.of("isPublic", true))))
                .andExpect(okEnvelope());
        mvc.perform(get("/community/profiles/{id}", owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.sharedSessionCount").value(4));
    }
}
