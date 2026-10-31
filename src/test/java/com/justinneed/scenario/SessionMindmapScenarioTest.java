package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.core.type.TypeReference;

/**
 * GET /sessions/{id}/mindmap: 세션 마인드맵과 유닛 노드 인용. 접근 규칙은 GET /sessions/{id} 와 같다.
 * AI JSON 은 저장된 값이라 형식이 어긋나도 500 없이 그 항목만 건너뛴다.
 */
@DisplayName("세션 마인드맵")
class SessionMindmapScenarioTest extends ScenarioTestSupport {

    private static final String UNIT = "https://x.example.com/y#pid1";
    private static final String LINK = "https://x.example.com/y";

    // AI 출력(pipeline/output.py)과 같은 모양
    private static final String MINDMAP = """
            {"structureSource": "llm", "rootSource": "query", "repairs": [],
             "nodes": [
               {"id": "root:1", "type": "root", "unitRef": null, "mergedFrom": [], "score": 3.9, "keyword": "로마", "childCount": 2},
               {"id": "topic:0", "type": "topic", "unitRef": null, "mergedFrom": [], "score": 3.5, "keyword": "콜로세움", "childCount": 1},
               {"id": "https://x.example.com/y#pid1", "type": "unit", "unitRef": {"pageId": "https://x.example.com/y", "pid": "pid1"},
                "mergedFrom": [], "score": 3.5, "keyword": "입장권", "childCount": 0},
               {"id": "https://x.example.com/y#pid2", "type": "unit", "score": null, "keyword": "컷오프 밖", "childCount": 0}],
             "edges": [
               {"from": "root:1", "to": "topic:0", "type": "hierarchy", "primary": true, "order": 3},
               {"from": "topic:0", "to": "https://x.example.com/y#pid1", "type": "hierarchy", "primary": false},
               {"from": "https://x.example.com/y#pid1", "to": "https://x.example.com/y#pid2", "type": "related"}]}
            """;

    private static final String SELECTED = """
            [{"unitKey": "https://x.example.com/y#pid1", "pageId": "https://x.example.com/y", "pid": "pid1",
              "pageTitle": "글 제목", "pageUrl": "https://x.example.com/y", "text": "원문 문단", "score": 3.5,
              "firstSeenAt": "2026-10-04T09:40:00+00:00", "mergedFrom": []}]
            """;

    private static final LocalDateTime VISITED_AT = LocalDateTime.of(2026, 10, 4, 18, 32, 52);

    @Autowired
    private SessionResultRepository results;

    @Autowired
    private JdbcTemplate jdbc;

    private Member me;
    private BrowsingSession mine;

    @BeforeEach
    void seed() {
        me = member("sprout");
        mine = session(me, "내 세션", "2026-10-04T18:00:00", "2026-10-04T19:00:00", 1, false, false,
                List.of("로마"), List.of(new Source("글 제목", LINK, "대표 발췌", VISITED_AT)), "## 본문");
    }

    private void saveResult(BrowsingSession session, String mindmap, String selected) {
        SessionResult result = new SessionResult(session.getId(), session.getUserId());
        result.replace(mindmap == null ? null : objectMapper.readValue(mindmap, new TypeReference<Map<String, Object>>() {
                }),
                objectMapper.readValue(selected, new TypeReference<List<Map<String, Object>>>() {
                }), List.of(), Map.of(), List.of(), List.of(), "global", "C");
        results.saveAndFlush(result);
    }

    private ResultActions mindmap(BrowsingSession session, Member viewer)
            throws Exception {
        return mvc.perform(get("/sessions/{id}/mindmap", session.getId()).with(as(viewer)));
    }

    @Test
    @DisplayName("노드와 엣지를 화면에 필요한 값만 내려주고, 컷오프 유닛 노드에는 원문 인용이 붙는다")
    void nodesEdgesAndCitation() throws Exception {
        saveResult(mine, MINDMAP, SELECTED);

        mindmap(mine, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(4)))
                .andExpect(jsonPath("$.data.nodes[0].id").value("root:1"))
                .andExpect(jsonPath("$.data.nodes[0].type").value("root"))
                .andExpect(jsonPath("$.data.nodes[0].keyword").value("로마"))
                .andExpect(jsonPath("$.data.nodes[0].score").value(3.9))
                .andExpect(jsonPath("$.data.nodes[0].childCount").value(2))
                .andExpect(jsonPath("$.data.nodes[0].citation").value(nullValue()))
                .andExpect(jsonPath("$.data.nodes[0].unitRef").doesNotExist())
                .andExpect(jsonPath("$.data.nodes[2].id").value(UNIT))
                .andExpect(jsonPath("$.data.nodes[2].type").value("unit"))
                .andExpect(jsonPath("$.data.nodes[2].citation.title").value("글 제목"))
                .andExpect(jsonPath("$.data.nodes[2].citation.url").value(LINK))
                .andExpect(jsonPath("$.data.nodes[2].citation.excerpt").value("원문 문단"))
                .andExpect(jsonPath("$.data.nodes[2].citation.visitedAt").value("2026-10-04T18:32:52"))
                // 컷오프에 없는 유닛 노드는 인용이 없고, 점수가 없으면 null
                .andExpect(jsonPath("$.data.nodes[3].citation").value(nullValue()))
                .andExpect(jsonPath("$.data.nodes[3].score").value(nullValue()))
                .andExpect(jsonPath("$.data.edges", hasSize(3)))
                .andExpect(jsonPath("$.data.edges[0].from").value("root:1"))
                .andExpect(jsonPath("$.data.edges[0].to").value("topic:0"))
                .andExpect(jsonPath("$.data.edges[0].type").value("hierarchy"))
                .andExpect(jsonPath("$.data.edges[0].primary").value(true))
                .andExpect(jsonPath("$.data.edges[0].order").doesNotExist())
                .andExpect(jsonPath("$.data.edges[1].primary").value(false))
                // related 엣지는 primary 가 없다
                .andExpect(jsonPath("$.data.edges[2].type").value("related"))
                .andExpect(jsonPath("$.data.edges[2].primary").value(false));
    }

    @Test
    @DisplayName("출처에 없는 링크의 인용은 유닛을 처음 본 시각(KST)을 쓴다")
    void citationFallsBackToFirstSeenAt() throws Exception {
        BrowsingSession bare = session(me, "출처 없음", "2026-10-04T18:00:00", "2026-10-04T19:00:00", 1, false, false,
                List.of(), List.of(), null);
        saveResult(bare, MINDMAP, SELECTED);

        mindmap(bare, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes[2].citation.visitedAt").value("2026-10-04T18:40:00"));
    }

    @Test
    @DisplayName("모르는 type · 형식이 깨진 노드와 없는 노드를 가리키는 엣지는 500 없이 건너뛴다")
    void malformedItemsAreSkipped() throws Exception {
        saveResult(mine, """
                {"nodes": [
                   "문자열 노드", 7, null,
                   {"id": "root:1", "type": "root", "keyword": "남는 노드", "score": "숫자 아님", "childCount": "많음"},
                   {"id": "x", "type": "group", "keyword": "모르는 type"},
                   {"id": "y", "keyword": "type 없음"},
                   {"id": 3, "type": "topic", "keyword": "id 가 숫자"},
                   {"id": "z", "type": "topic"},
                   {"id": "blank", "type": "topic", "keyword": "  "},
                   {"id": "root:1", "type": "topic", "keyword": "같은 id"},
                   {"id": "topic:0", "type": "topic", "keyword": "남는 주제"}],
                 "edges": [
                   "문자열 엣지", null,
                   {"from": "root:1", "to": "topic:0", "type": "hierarchy", "primary": "yes"},
                   {"from": "root:1", "to": "x", "type": "hierarchy", "primary": true},
                   {"from": "x", "to": "topic:0", "type": "hierarchy", "primary": true},
                   {"from": 1, "to": "topic:0", "type": "hierarchy"},
                   {"from": "root:1", "type": "hierarchy"},
                   {"from": "root:1", "to": "topic:0", "type": "다른 관계"},
                   {"from": "root:1", "to": "topic:0"}]}
                """, """
                [{"pageUrl": "https://a"}, {"unitKey": 1, "pageUrl": "https://a"}, {"unitKey": "root:1"},
                 {"unitKey": "topic:0", "pageUrl": "https://a", "pageTitle": "유닛이 아닌 노드", "text": "인용 없음"}]
                """);

        mindmap(mine, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes[*].id").value(contains("root:1", "topic:0")))
                .andExpect(jsonPath("$.data.nodes[0].keyword").value("남는 노드"))
                .andExpect(jsonPath("$.data.nodes[0].score").value(nullValue()))
                .andExpect(jsonPath("$.data.nodes[0].childCount").value(0))
                .andExpect(jsonPath("$.data.nodes[1].citation").value(nullValue()))
                .andExpect(jsonPath("$.data.edges", hasSize(1)))
                .andExpect(jsonPath("$.data.edges[0].primary").value(false));
    }

    @Test
    @DisplayName("nodes · edges 가 배열이 아니거나 마인드맵이 비어 있어도 빈 그래프다")
    void malformedTopLevelIsEmpty() throws Exception {
        saveResult(mine, """
                {"nodes": "없음", "edges": {"a": 1}}
                """, "[]");
        mindmap(mine, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.edges", hasSize(0)));
    }

    @Test
    @DisplayName("저장된 mindmap 이 객체가 아니거나 selected_units 에 객체가 아닌 값이 있어도 500 없이 응답한다")
    void wrongColumnShapeIsNot500() throws Exception {
        saveResult(mine, MINDMAP, SELECTED);

        jdbc.update("update session_results set selected_units = JSON '[1, \"x\", null]' where session_id = ?", mine.getId());
        mindmap(mine, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(4)))
                .andExpect(jsonPath("$.data.nodes[2].citation").value(nullValue()));

        jdbc.update("update session_results set mindmap = JSON '[]' where session_id = ?", mine.getId());
        mindmap(mine, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.edges", hasSize(0)));
    }

    @Test
    @DisplayName("분석 결과 행이 없으면 소유자에게 빈 그래프다")
    void noResultRow() throws Exception {
        mindmap(mine, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.edges", hasSize(0)));
    }

    @Test
    @DisplayName("마인드맵이 null 인 결과 행도 빈 그래프다")
    void nullMindmap() throws Exception {
        saveResult(mine, null, "[]");

        mindmap(mine, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)));
    }

    @ParameterizedTest
    @EnumSource(value = SessionStatus.class, names = "COMPLETED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("분석이 끝나지 않은 세션은 결과 행이 있어도 소유자에게 빈 그래프다")
    void unfinishedSession(SessionStatus status) throws Exception {
        BrowsingSession unfinished = sessionWithStatus(me, "진행 중", "2026-10-30T09:00:00", null, status, List.of());
        saveResult(unfinished, MINDMAP, SELECTED);

        mindmap(unfinished, me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.edges", hasSize(0)));
    }

    @Test
    @DisplayName("없거나 지운 세션은 404, 남의 비공개 세션은 403")
    void accessErrors() throws Exception {
        Wanderer wanderer = seedWanderer();
        saveResult(wanderer.slow(), MINDMAP, SELECTED);

        mvc.perform(get("/sessions/{id}/mindmap", 999_999).with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
        mindmap(wanderer.slow(), me)
                .andExpect(errorEnvelope(HttpStatus.FORBIDDEN, "비공개 세션이라 볼 수 없어요."));

        mine.delete();
        sessions.saveAndFlush(mine);
        mindmap(mine, me)
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, "세션을 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("다른 사용자도 내용까지 공개된 세션의 마인드맵은 본다")
    void foreignPublicSessionWithContent() throws Exception {
        Wanderer wanderer = seedWanderer();
        CommunityProfile profile = communityProfiles.findById(wanderer.id()).orElseThrow();
        profile.changeVisibility(NodeVisibility.CONTENT);
        communityProfiles.saveAndFlush(profile);
        saveResult(wanderer.prague(), MINDMAP, SELECTED);

        mindmap(wanderer.prague(), me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(4)))
                .andExpect(jsonPath("$.data.nodes[2].citation.excerpt").value("원문 문단"));
    }

    @Test
    @DisplayName("작성자의 노드 공개 범위가 URL 만이면 다른 사용자에게 마인드맵 내용을 주지 않고, 소유자는 그대로 본다")
    void urlOnlyProfileHidesMindmap() throws Exception {
        Wanderer wanderer = seedWanderer();
        saveResult(wanderer.prague(), MINDMAP, SELECTED);

        mindmap(wanderer.prague(), me)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.edges", hasSize(0)));
        mindmap(wanderer.prague(), wanderer.member())
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(4)));
    }

    @Test
    @DisplayName("세션 상세 응답은 마인드맵을 싣지 않는다")
    void detailStaysSeparate() throws Exception {
        saveResult(mine, MINDMAP, SELECTED);

        mvc.perform(get("/sessions/{id}", mine.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmap").doesNotExist())
                .andExpect(jsonPath("$.data.nodes").doesNotExist());
    }
}
