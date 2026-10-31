package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.justinneed.auth.domain.Member;
import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** 공개 그래프의 원본: 내용까지는 AI 마인드맵(hierarchy), URL만은 태그 → URL. */
@DisplayName("공개 그래프의 원본")
class CommunityGraphSourceScenarioTest extends ScenarioTestSupport {

    private static final String LINK = "https://x.example.com/y";
    private static final String UNIT = LINK + "#pid1";
    private static final String MINDMAP = """
            {"structureSource": "llm",
             "nodes": [
               {"id": "root:1", "type": "root", "keyword": "로마", "score": 1.0, "childCount": 1},
               {"id": "topic:0", "type": "topic", "keyword": "콜로세움", "score": 0.9, "childCount": 1},
               {"id": "https://x.example.com/y#pid1", "type": "unit", "keyword": "입장권", "score": 0.5, "childCount": 0},
               {"id": "bad", "type": "weird", "keyword": "모르는 타입"}],
             "edges": [
               {"from": "root:1", "to": "topic:0", "type": "hierarchy", "primary": true},
               {"from": "topic:0", "to": "https://x.example.com/y#pid1", "type": "hierarchy", "primary": true},
               {"from": "root:1", "to": "https://x.example.com/y#pid1", "type": "related"},
               {"from": "root:1", "to": "bad", "type": "hierarchy"}]}
            """;
    private static final String SELECTED = """
            [{"unitKey": "https://x.example.com/y#pid1", "pageTitle": "글 제목", "pageUrl": "https://x.example.com/y",
              "text": "원문 문단", "score": 0.5, "firstSeenAt": "2026-10-04T09:40:00+00:00"}]
            """;

    @Autowired private SessionResultRepository results;
    @Autowired private CommunityCardRepository cards;
    @Autowired private JdbcTemplate jdbc;

    private Member owner;
    private Member viewer;
    private BrowsingSession rome;

    @BeforeEach
    void seed() {
        owner = member("owner");
        viewer = member("viewer");
        rome = session(owner, "로마", "2026-04-26T21:00:00", "2026-04-26T22:00:00", 1, false, false,
                List.of("로마", "비밀"), List.of(new Source("글 제목", LINK, "발췌")), "본문");
    }

    private void saveResult(String mindmap, String selected) {
        results.saveAndFlush(new SessionResult(rome.getId(), owner.getId()));
        jdbc.update("update session_results set mindmap = JSON '" + mindmap + "', selected_units = JSON '" + selected
                + "' where session_id = ?", rome.getId());
    }

    private void setPublic() throws Exception {
        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(owner)).with(json(Map.of("isPublic", true))))
                .andExpect(okEnvelope());
    }

    private void showContent() throws Exception {
        mvc.perform(patch("/community/me/visibility").with(as(owner)).with(json(Map.of("visibility", "CONTENT"))))
                .andExpect(okEnvelope());
    }

    private String mindmap() {
        return "/community/profiles/" + owner.getId() + "/mindmap";
    }

    private Long nodeId(String keyword) throws Exception {
        String body = mvc.perform(get(mindmap()).with(as(viewer))).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        List<Number> found = JsonPath.read(body, "$.data.nodes[?(@.label == '" + keyword + "')].nodeId");
        return found.get(0).longValue();
    }

    @Test
    @DisplayName("URL 만 공개면 분석 결과가 있어도 태그 → URL 이다")
    void urlOnlyUsesTagToUrl() throws Exception {
        saveResult(MINDMAP, SELECTED);
        setPublic();

        mvc.perform(get(mindmap()).with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes[*].label").value(contains(LINK)))
                .andExpect(jsonPath("$.data.nodes[0].content").doesNotExist())
                .andExpect(jsonPath("$.data.edges", hasSize(2)))
                .andExpect(jsonPath("$.data.edges[*].from", everyItem(startsWith("tag:"))));
    }

    @Test
    @DisplayName("내용까지 공개면 AI 노드와 hierarchy 연결선이고, 태그는 뿌리에 이어진다. related · 없는 노드 연결선은 빠진다")
    void contentUsesAiMindmap() throws Exception {
        saveResult(MINDMAP, SELECTED);
        setPublic();
        showContent();

        Long root = nodeId("로마");
        Long topic = nodeId("콜로세움");
        Long unit = nodeId("입장권");
        mvc.perform(get(mindmap()).with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes[*].label").value(containsInAnyOrder("로마", "콜로세움", "입장권")))
                .andExpect(jsonPath("$.data.nodes[?(@.label == '입장권')].sourceUrl").value(contains(LINK)))
                .andExpect(jsonPath("$.data.nodes[?(@.label == '입장권')].content").value(contains("원문 문단")))
                .andExpect(jsonPath("$.data.nodes[*].visibilityScope", everyItem(org.hamcrest.Matchers.is("CONTENT"))))
                .andExpect(jsonPath("$.data.edges[?(@.from =~ /node:.*/)].from").value(contains(
                        "node:" + root, "node:" + topic)))
                .andExpect(jsonPath("$.data.edges[?(@.from =~ /node:.*/)].to").value(contains(
                        "node:" + topic, "node:" + unit)))
                .andExpect(jsonPath("$.data.edges[?(@.from =~ /tag:.*/)].to").value(contains(
                        "node:" + root, "node:" + root)))
                .andExpect(jsonPath("$.data.topHashtags[0].nodeCount").value(3));
        mvc.perform(get("/community/profiles/{id}", owner.getId()).with(as(viewer)))
                .andExpect(jsonPath("$.data.totalNodeCount").value(3));
    }

    @Test
    @DisplayName("공개 범위를 바꿔도 다시 쓰지 않고 바로 원본이 바뀐다 (노드 행은 둘 다 미리 있다)")
    void visibilityFlipNeedsNoSync() throws Exception {
        saveResult(MINDMAP, SELECTED);
        setPublic();
        Long urlNode = readLong(mvc.perform(get(mindmap()).with(as(viewer))), "$.data.nodes[0].nodeId");

        showContent();
        mvc.perform(get(mindmap()).with(as(viewer))).andExpect(jsonPath("$.data.nodes", hasSize(3)));

        mvc.perform(patch("/community/me/visibility").with(as(owner)).with(json(Map.of("visibility", "URL_ONLY"))))
                .andExpect(okEnvelope());
        assertThat(readLong(mvc.perform(get(mindmap()).with(as(viewer))), "$.data.nodes[0].nodeId")).isEqualTo(urlNode);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[1, 2, 3]",
            "\"문자열\"",
            "{\"nodes\": \"노드 아님\", \"edges\": 3}",
            "{\"nodes\": [null, 3, {\"id\": 5}], \"edges\": [null, {\"from\": 1}]}"
    })
    @DisplayName("저장된 AI JSON 모양이 어긋나면 그 세션의 AI 노드만 건너뛴다 (쓰기 · 읽기 모두 500 이 아니다)")
    void malformedMindmapIsSkipped(String malformed) throws Exception {
        saveResult(malformed, "[]");
        setPublic();
        showContent();

        assertThat(cards.findById(rome.getId()).orElseThrow().getAiNodeCount()).isZero();
        mvc.perform(get(mindmap()).with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nodes", hasSize(0)))
                .andExpect(jsonPath("$.data.edges", hasSize(0)));
        mvc.perform(get("/community/profiles/{id}", owner.getId()).with(as(viewer))).andExpect(okEnvelope());
    }

    @Test
    @DisplayName("AI 노드도 북마크 · 나도 탐색중 대상이고, 위로 올라가 뿌리에 달린 태그를 찾는다")
    void aiNodeIsATarget() throws Exception {
        saveResult(MINDMAP, SELECTED);
        setPublic();
        showContent();
        Long unit = nodeId("입장권");

        mvc.perform(patch("/community/exploring").with(as(viewer))
                        .with(json(Map.of("targetType", "NODE", "targetId", unit, "exploring", true))))
                .andExpect(okEnvelope());
        mvc.perform(post("/community/bookmarks").with(as(viewer)).with(json(Map.of(
                        "targetType", "NODE", "targetIds", List.of(unit), "saveType", "MINI_MINDMAP",
                        "sourceMindmapId", owner.getId()))))
                .andExpect(status().isCreated());

        mvc.perform(get("/community/me/bookmarks/mindmaps").with(as(viewer)))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].label").value(contains("입장권")))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(containsInAnyOrder("로마", "비밀")))
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(2)));
    }

    @Test
    @DisplayName("내용까지로 바꾸기 전에 URL 노드로 저장한 북마크 · 나도 탐색중은 계속 동작한다")
    void urlNodeIdsKeepWorkingAfterContent() throws Exception {
        saveResult(MINDMAP, SELECTED);
        setPublic();
        Long urlNode = readLong(mvc.perform(get(mindmap()).with(as(viewer))), "$.data.nodes[0].nodeId");
        mvc.perform(patch("/community/exploring").with(as(viewer))
                        .with(json(Map.of("targetType", "NODE", "targetId", urlNode, "exploring", true))))
                .andExpect(okEnvelope());
        Long bookmark = readLong(mvc.perform(post("/community/bookmarks").with(as(viewer)).with(json(Map.of(
                "targetType", "NODE", "targetIds", List.of(urlNode), "saveType", "MINI_MINDMAP",
                "sourceMindmapId", owner.getId())))).andExpect(status().isCreated()), "$.data.bookmarkId");

        showContent();

        mvc.perform(get("/community/exploring").param("targetType", "NODE").param("targetId", urlNode.toString())
                        .with(as(viewer)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.exploring").value(true))
                .andExpect(jsonPath("$.data.exploringCount").value(1));
        mvc.perform(get("/community/me/bookmarks/mindmaps").with(as(viewer)))
                .andExpect(jsonPath("$.data.mindmaps[*].nodes[*].sourceUrl").value(contains(LINK)));
        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmark).with(as(viewer)))
                .andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(contains(urlNode.intValue())));
    }
}
