package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 세션 상태 규칙. 분석이 끝나기 전에는 내용을 바꿀 수 없고(S-08), 지운 세션은 분석 결과 · 개인화에서 빠진다(S-10),
 * 끝나지 않은 세션은 공개 화면에 나오지 않는다.
 */
@DisplayName("세션 상태 규칙")
class SessionStatusScenarioTest extends ScenarioTestSupport {

    private static final String NOT_COMPLETED = "분석이 끝난 세션만 수정할 수 있습니다.";
    private static final String NOT_IN_PROGRESS = "분석 중인 세션이 아닙니다.";
    private static final String RESULT = """
            {"modelSource": "global", "scoreMethod": "C",
             "state": {"unitFeatures": [{"x": 1}], "userModel": {"k": 4}},
             "mindmap": {"structureSource": "llm",
                         "nodes": [{"id": "root:1", "type": "root", "keyword": "로마", "score": 1.0, "childCount": 0}],
                         "edges": []},
             "essay": {"source": "llm", "title": "AI 제목", "sections": [{"heading": "소제목", "body": "본문"}]},
             "selected": [], "analysis": {"units": [], "summary": {}}, "pages": []}
            """;

    @Autowired
    private SessionRawPayloadRepository rawPayloads;

    @Autowired
    private SessionResultRepository results;

    @Autowired
    private AiClusterModelRepository models;

    @Autowired
    private AiInternalToken internalToken;

    @Autowired
    private EntityManager entityManager;

    private Member me;

    @BeforeEach
    void seed() {
        me = member("sprout");
    }

    private BrowsingSession unfinished(SessionStatus status) {
        return sessionWithStatus(me, "진행 중", "2026-10-30T09:00:00", null, status, List.of("로마"));
    }

    private static Stream<Arguments> contentChanges() {
        return Stream.of(SessionStatus.COLLECTING, SessionStatus.READY, SessionStatus.ANALYZING, SessionStatus.FAILED)
                .flatMap(status -> Stream.of(
                        Arguments.of(status, Map.of("title", "바꾼 제목")),
                        Arguments.of(status, Map.of("editedMarkdown", "바꾼 본문")),
                        Arguments.of(status, Map.of("tags", List.of("바꾼태그"))),
                        Arguments.of(status, Map.of("isPublic", true)),
                        Arguments.of(status, Map.of("isFavorite", true, "title", "즐겨찾기와 같이"))));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("contentChanges")
    @DisplayName("분석이 끝나기 전 제목 · 본문 · 태그 · 공개 수정은 409 이고 아무것도 바뀌지 않는다 (함께 보낸 즐겨찾기도)")
    void contentChangeBeforeCompletion(SessionStatus status, Map<String, Object> body) throws Exception {
        BrowsingSession session = unfinished(status);

        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(me)).with(json(body)))
                .andExpect(errorEnvelope(HttpStatus.CONFLICT, NOT_COMPLETED));

        mvc.perform(get("/sessions/{id}", session.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.title").value("진행 중"))
                .andExpect(jsonPath("$.data.tags").value(contains("로마")))
                .andExpect(jsonPath("$.data.isPublic").value(false))
                .andExpect(jsonPath("$.data.isFavorite").value(false))
                .andExpect(jsonPath("$.data.status").value(status.name()));
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("즐겨찾기만 바꾸는 건 분석이 끝나기 전에도 된다")
    void favouriteBeforeCompletion(SessionStatus status) throws Exception {
        BrowsingSession session = unfinished(status);

        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(me)).with(json(Map.of("isFavorite", true))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isFavorite").value(true));
    }

    @ParameterizedTest
    @EnumSource(SessionStatus.class)
    @DisplayName("세션 삭제는 어떤 상태에서도 된다")
    void deleteInEveryStatus(SessionStatus status) throws Exception {
        BrowsingSession session = unfinished(status);

        mvc.perform(delete("/sessions/{id}", session.getId()).with(as(me))).andExpect(okEnvelope());

        mvc.perform(get("/sessions/{id}", session.getId()).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("isPublic 을 안 보낸 수정은 공개 세션을 공개로 둔다")
    void patchWithoutIsPublicKeepsPublic() throws Exception {
        BrowsingSession session = session(me, "공개 세션", "2026-10-30T09:00:00", "2026-10-30T10:00:00", 1, false, true,
                List.of("로마"), List.of(), "본문");

        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(me)).with(json(Map.of("title", "새 제목"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.isPublic").value(true));
    }

    @Test
    @DisplayName("사용자가 본문을 고쳐도 AI 줄글 원본(summaries.essay)은 남는다")
    void userEditKeepsEssay() throws Exception {
        BrowsingSession session = session(me, "완료", "2026-10-30T09:00:00", "2026-10-30T10:00:00", 1, false, false,
                List.of("로마"), List.of(), "AI 본문");
        session.getSummary().replaceEssay(Map.of("title", "AI 제목", "source", "llm"), "AI 본문");
        sessions.saveAndFlush(session);

        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(me)).with(json(Map.of("editedMarkdown", "내 본문"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.summary.markdown").value("내 본문"));

        entityManager.flush();
        entityManager.clear();
        var summary = sessions.findById(session.getId()).orElseThrow().getSummary();
        assertThat(summary.getMarkdown()).isEqualTo("내 본문");
        assertThat(summary.getEssay()).containsEntry("title", "AI 제목");
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("분석이 끝나기 전 세션은 핀고정할 수 없다 (409, 공개로도 바뀌지 않는다)")
    void pinBeforeCompletion(SessionStatus status) throws Exception {
        BrowsingSession session = unfinished(status);

        mvc.perform(post("/community/me/pinned-sessions").with(as(me)).with(json(Map.of("sessionId", session.getId()))))
                .andExpect(errorEnvelope(HttpStatus.CONFLICT, NOT_COMPLETED));

        mvc.perform(get("/sessions/{id}", session.getId()).with(as(me)))
                .andExpect(jsonPath("$.data.isPublic").value(false));
        assertThat(communityProfiles.findById(me.getId()).map(CommunityProfile::getPinnedSessionIds).orElse(List.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("이미 공개로 저장된 미완료 세션은 커뮤니티 공개 화면에 나오지 않는다")
    void unfinishedPublicRowStaysHidden() throws Exception {
        Member viewer = member("foodtrip");
        BrowsingSession bad = session(me, "실패한 공개 세션", "2026-10-30T09:00:00", "2026-10-30T10:00:00", 1, false, true,
                List.of("로마"), List.of(new Source("글", "https://example.com/a", "발췌")), "본문");
        ReflectionTestUtils.setField(bad, "status", SessionStatus.FAILED);
        sessions.saveAndFlush(bad);
        cardSync.sync(bad);
        CommunityProfile profile = new CommunityProfile(me.getId());
        profile.replacePinnedSessionIds(List.of(bad.getId()));
        communityProfiles.saveAndFlush(profile);

        mvc.perform(get("/community/profiles/{id}/shared-sessions", me.getId()).with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions", hasSize(0)));
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", me.getId(), bad.getId()).with(as(viewer)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
        mvc.perform(get("/community/profiles/{id}/mindmap", me.getId()).with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.hashtags", hasSize(0)));
        mvc.perform(get("/community/search/mindmaps").param("q", "로마").with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
    }

    @Test
    @DisplayName("지운 세션에 AI 결과가 오면 409 이고 아무것도 저장하지 않는다 (결과 · 요약 · 개인 모델 · 원본 보관 기한)")
    void callbackOnDeletedSession() throws Exception {
        BrowsingSession session = analyzingWithRaw();
        session.delete();
        sessions.saveAndFlush(session);

        result(session, "/result", RESULT)
                .andExpect(errorEnvelope(HttpStatus.CONFLICT, NOT_IN_PROGRESS));

        entityManager.flush();
        entityManager.clear();
        BrowsingSession stored = sessions.findById(session.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(SessionStatus.ANALYZING);
        assertThat(stored.getTitle()).isEqualTo("분석 중");
        assertThat(stored.getSummary()).isNull();
        assertThat(results.findById(session.getId())).isEmpty();
        assertThat(models.findByScopeAndUserId(ClusterModelScope.USER, me.getId())).isEmpty();
        assertThat(rawPayloads.findById(session.getId()).orElseThrow().getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("지운 세션에 분석 실패가 와도 409 이고 상태는 그대로다")
    void failureCallbackOnDeletedSession() throws Exception {
        BrowsingSession session = analyzingWithRaw();
        session.delete();
        sessions.saveAndFlush(session);

        result(session, "/failure", "{\"reason\": \"x\"}")
                .andExpect(errorEnvelope(HttpStatus.CONFLICT, NOT_IN_PROGRESS));

        entityManager.flush();
        entityManager.clear();
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.ANALYZING);
    }

    private BrowsingSession analyzingWithRaw() {
        BrowsingSession session = sessionWithStatus(me, "분석 중", "2026-10-30T09:00:00", "2026-10-30T10:00:00",
                SessionStatus.ANALYZING, List.of());
        rawPayloads.saveAndFlush(new SessionRawPayload(session.getId(), me.getId(), Map.of("pages", List.of()),
                LocalDateTime.now()));
        return session;
    }

    private ResultActions result(BrowsingSession session, String path, String body)
            throws Exception {
        return mvc.perform(post("/internal/ai/sessions/{id}" + path, session.getId())
                .header(AiInternalToken.HEADER, internalToken.value())
                .with(json(body)));
    }
}
