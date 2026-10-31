package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.justinneed.auth.domain.Member;
import com.justinneed.community.bookmark.BookmarkSnapshot;
import com.justinneed.community.bookmark.CommunityBookmark;
import com.justinneed.community.bookmark.CommunityBookmarkRepository;
import com.justinneed.community.bookmark.SaveType;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/** 북마크는 원본의 지금 상태를 보여주고, 저장된 스냅샷은 그대로 남는다. 묶음 이름 · 되돌리기(다시 저장)까지. */
@DisplayName("북마크: 원본의 지금 상태")
class BookmarkCurrentStateScenarioTest extends ScenarioTestSupport {

    private static final String SAVED = "/community/me/bookmarks/mindmaps";
    private static final String BUNDLES = "/community/me/bookmarks/hashtag-bundles";
    private static final String BOOKMARKS = "/community/bookmarks";
    private static final String CASTLE = "https://www.hrad.cz/en/prague-castle-for-visitors";
    private static final String PRAGUE_EU = "https://www.prague.eu/en";
    private static final String WIEN = "https://www.wien.info/en";
    private static final String BELVEDERE = "https://www.belvedere.at/en";
    private static final String NIGHTJET = "https://www.nightjet.com/en";
    private static final String DEFAULT_TITLE = "저장한 마인드맵";
    private static final String OLD_TITLE = "Saved community mindmap";
    private static final String PRAGUE_TITLE = "프라하 1박 일정 짜기";
    private static final String PRAGUE_SNIPPET = "카를교 → 프라하 성 순서로 걷는다.";
    private static final String CARD_KEYWORD = "AI 체코 뿌리";
    private static final String TOPIC_KEYWORD = "AI 성 주제";
    private static final String UNIT_KEYWORD = "AI 입장권 유닛";
    private static final String UNIT_TEXT = "AI 인용 원문 문단";

    @Autowired private CommunityBookmarkRepository rows;
    @Autowired private SessionResultRepository results;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Member me;
    private Wanderer wanderer;

    @BeforeEach
    void seed() {
        me = member("sprout");
        wanderer = seedWanderer();
    }

    // ---------- 준비 ----------

    private static String aiMindmap(String prefix, String url) {
        return """
                {"structureSource": "llm",
                 "nodes": [
                   {"id": "root:1", "type": "root", "keyword": "%1$s%2$s", "score": 1.0, "childCount": 1},
                   {"id": "topic:0", "type": "topic", "keyword": "%1$s%3$s", "score": 0.9, "childCount": 1},
                   {"id": "%4$s#pid1", "type": "unit", "keyword": "%1$s%5$s", "score": 0.5, "childCount": 0}],
                 "edges": [
                   {"from": "root:1", "to": "topic:0", "type": "hierarchy", "primary": true},
                   {"from": "topic:0", "to": "%4$s#pid1", "type": "hierarchy", "primary": true}]}
                """.formatted(prefix, CARD_KEYWORD, TOPIC_KEYWORD, url, UNIT_KEYWORD);
    }

    private static String selected(String url) {
        return """
                [{"unitKey": "%1$s#pid1", "pageTitle": "AI 글 제목", "pageUrl": "%1$s",
                  "text": "%2$s", "score": 0.5, "firstSeenAt": "2026-10-04T09:40:00+00:00"}]
                """.formatted(url, UNIT_TEXT);
    }

    private void analyse(BrowsingSession session, String prefix, String url) {
        results.saveAndFlush(new SessionResult(session.getId(), session.getUserId()));
        replaceAnalysis(session, prefix, url);
    }

    private void replaceAnalysis(BrowsingSession session, String prefix, String url) {
        jdbc.update("update session_results set mindmap = JSON '" + aiMindmap(prefix, url)
                + "', selected_units = JSON '" + selected(url) + "' where session_id = ?", session.getId());
        cardSync.sync(session);
    }

    private void setScope(NodeVisibility scope) throws Exception {
        mvc.perform(patch("/community/me/visibility").with(as(wanderer.member()))
                .with(json(Map.of("visibility", scope.name())))).andExpect(okEnvelope());
    }

    private void editSession(BrowsingSession session, Map<String, Object> body) throws Exception {
        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(wanderer.member())).with(json(body)))
                .andExpect(okEnvelope());
    }

    private void like(Member actor, BrowsingSession session) throws Exception {
        mvc.perform(patch("/community/mindmaps/{id}/like", session.getId()).with(as(actor))
                .with(json(Map.of("liked", true)))).andExpect(okEnvelope());
    }

    private Long saveCard(BrowsingSession session) throws Exception {
        return readLong(mvc.perform(patch("/community/mindmaps/{id}/bookmark", session.getId()).with(as(me))
                .with(json(Map.of("bookmarked", true)))).andExpect(okEnvelope()), "$.data.bookmarkId");
    }

    private ResultActions save(String targetType, List<Long> ids, String saveType, String name) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("targetType", targetType);
        body.put("targetIds", ids);
        body.put("saveType", saveType);
        body.put("sourceMindmapId", wanderer.id());
        body.put("name", name);
        return mvc.perform(post(BOOKMARKS).with(as(me)).with(json(body)));
    }

    private Long saveNodes(List<Long> ids) throws Exception {
        return readLong(save("NODE", ids, "MINI_MINDMAP", null).andExpect(status().isCreated()), "$.data.bookmarkId");
    }

    private Long saveTags(String... hashtags) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String hashtag : hashtags) {
            ids.add(hashtagId(wanderer.member(), hashtag));
        }
        return readLong(save("HASHTAG", ids, "MINI_MINDMAP", null).andExpect(status().isCreated()), "$.data.bookmarkId");
    }

    private Long node(String sourceUrl) throws Exception {
        return nodeId(me, wanderer.member(), sourceUrl);
    }

    private Long aiNode(String keyword) throws Exception {
        List<Number> found = JsonPath.read(body(graph()), "$.data.nodes[?(@.label == '" + keyword + "')].nodeId");
        return found.get(0).longValue();
    }

    private ResultActions graph() throws Exception {
        return mvc.perform(get("/community/profiles/{id}/mindmap", wanderer.id()).with(as(me)));
    }

    private ResultActions saved() throws Exception {
        return mvc.perform(get(SAVED).with(as(me))).andExpect(okEnvelope());
    }

    private ResultActions original(Long bookmarkId) throws Exception {
        return mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me))).andExpect(okEnvelope());
    }

    private static String body(ResultActions result) throws Exception {
        return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private void expectHighlightsAreGraphNodes(Long bookmarkId) throws Exception {
        String response = body(original(bookmarkId));
        List<Number> highlighted = JsonPath.read(response, "$.data.highlightedNodeIds");
        List<Number> graphNodes = JsonPath.read(response, "$.data.mindmap.nodes[*].nodeId");
        assertThat(highlighted).isNotEmpty();
        assertThat(graphNodes).containsAll(highlighted);
    }

    // ---------- 응답 모양 ----------

    @Test
    @DisplayName("미니 마인드맵 항목은 계약한 필드만 오고, code 없이 hasNext 가 온다")
    void mindmapItemShape() throws Exception {
        saveCard(wanderer.prague());

        String response = body(saved().andExpect(jsonPath("$.data.hasNext").value(false)));

        assertThat(JsonPath.<Map<String, Object>>read(response, "$.data")).containsOnlyKeys("mindmaps", "hasNext");
        assertThat(JsonPath.<Map<String, Object>>read(response, "$.data.mindmaps[0]")).containsOnlyKeys(
                "bookmarkId", "sessionId", "sourceMindmapId", "owner", "title", "snippet", "hashtags", "nodes", "edges",
                "nodeCount", "likeCount", "savedAt", "saveType", "targetType", "targetIds", "cardBookmark");
        assertThat(JsonPath.<Map<String, Object>>read(response, "$.data.mindmaps[0].hashtags[0]"))
                .containsOnlyKeys("hashtagId", "name");
    }

    @Test
    @DisplayName("해시태그 묶음 항목은 이름 · 다시 저장에 필요한 값까지 온다")
    void bundleItemShape() throws Exception {
        Long prague = hashtagId(wanderer.member(), "프라하");
        Long bookmarkId = readLong(save("HASHTAG", List.of(prague), "HASHTAG_BUNDLE", "동유럽 자료"), "$.data.bookmarkId");

        String response = body(mvc.perform(get(BUNDLES).with(as(me))).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bundles[0].bookmarkId").value(bookmarkId))
                .andExpect(jsonPath("$.data.bundles[0].name").value("동유럽 자료"))
                .andExpect(jsonPath("$.data.bundles[0].hashtags").value(contains("프라하")))
                .andExpect(jsonPath("$.data.bundles[0].targetType").value("HASHTAG"))
                .andExpect(jsonPath("$.data.bundles[0].targetIds").value(contains(prague.intValue())))
                .andExpect(jsonPath("$.data.bundles[0].saveType").value("HASHTAG_BUNDLE"))
                .andExpect(jsonPath("$.data.bundles[0].sourceMindmapId").value(wanderer.id())));

        assertThat(JsonPath.<Map<String, Object>>read(response, "$.data.bundles[0]")).containsOnlyKeys(
                "bookmarkId", "name", "hashtags", "savedAt", "saveType", "targetType", "targetIds", "sourceMindmapId");
    }

    // ---------- 세션 하나: 카드의 지금 상태 ----------

    @Test
    @DisplayName("카드 북마크는 세션 id 와 카드의 제목 · 요약 · 태그 · 좋아요 · 노드 수를 보여주고 카드 북마크 표시가 켜진다")
    void cardBookmarkShowsCurrentCard() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long id = saveCard(wanderer.prague());
        like(member("other"), wanderer.prague());

        saved().andExpect(jsonPath("$.data.mindmaps[0].bookmarkId").value(id))
                .andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(wanderer.prague().getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].cardBookmark").value(true))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.userId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(PRAGUE_TITLE))
                .andExpect(jsonPath("$.data.mindmaps[0].snippet").value(PRAGUE_SNIPPET))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(3))
                .andExpect(jsonPath("$.data.mindmaps[0].likeCount").value(1))
                // 태그 3개 → 뿌리, 뿌리 → 주제 → 유닛
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(5)));
    }

    @Test
    @DisplayName("노드를 골라 저장해도 노드가 한 세션이면 그 세션의 카드를 따른다. 노드 수는 카드 값이고 카드 북마크는 아니다")
    void singleSessionPickFollowsCard() throws Exception {
        saveNodes(List.of(node(CASTLE)));
        setScope(NodeVisibility.CONTENT);

        saved().andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(wanderer.prague().getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].cardBookmark").value(false))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(PRAGUE_TITLE))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(contains(CASTLE)))
                // 프라하 세션은 AI 결과가 없어 내용까지 카드의 노드 수가 0 이다
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(0));
    }

    @Test
    @DisplayName("작성자가 제목 · 본문을 고치면 새 값이 보이고 스냅샷은 그대로다")
    void retitleShowsCurrentTitle() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long id = saveCard(wanderer.prague());
        BookmarkSnapshot before = rows.findById(id).orElseThrow().getSnapshot();

        editSession(wanderer.prague(), Map.of("title", "새 제목", "editedMarkdown", "# 머리말\n\n고친 첫 문단"));

        saved().andExpect(jsonPath("$.data.mindmaps[0].title").value("새 제목"))
                .andExpect(jsonPath("$.data.mindmaps[0].snippet").value("고친 첫 문단"));
        assertThat(rows.findById(id).orElseThrow().getSnapshot()).isEqualTo(before);
        assertThat(before.title()).isEqualTo(PRAGUE_TITLE);
    }

    @Test
    @DisplayName("작성자가 태그를 바꾸면 지금 태그와 그 id 로 연결선이 오고, 빠진 태그의 연결선은 남지 않는다. 스냅샷은 그대로다")
    void tagEditShowsCurrentTags() throws Exception {
        Long id = saveCard(wanderer.prague());
        Long removed = hashtagId(wanderer.member(), "카를교");
        BookmarkSnapshot before = rows.findById(id).orElseThrow().getSnapshot();

        editSession(wanderer.prague(), Map.of("tags", List.of("프라하", "새태그")));

        Long added = hashtagId(wanderer.member(), "새태그");
        saved().andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("프라하", "새태그")))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[1].hashtagId").value(added))
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(4)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[?(@.from == 'tag:" + added + "')]", hasSize(2)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[?(@.from == 'tag:" + removed + "')]", hasSize(0)));
        assertThat(rows.findById(id).orElseThrow().getSnapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("태그로 골라 저장해도 세션이 하나면 그 카드의 지금 태그 · 제목을 보여준다")
    void tagPickOnOneSessionFollowsCard() throws Exception {
        saveTags("카를교");
        setScope(NodeVisibility.CONTENT);

        saved().andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(wanderer.prague().getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].targetType").value("HASHTAG"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(PRAGUE_TITLE))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("프라하", "카를교", "체코")));
    }

    // ---------- URL만으로 바꾼 뒤 ----------

    @Test
    @DisplayName("내용까지 → URL만: 제목 · 요약 null, 노드는 URL 라벨만, URL 이 없는 AI 노드는 빠지고 AI 글이 응답에 없다. 되돌리면 다시 보인다")
    void contentToUrlOnlyDropsAiText() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long id = saveCard(wanderer.prague());
        Long unit = aiNode(UNIT_KEYWORD);
        BookmarkSnapshot before = rows.findById(id).orElseThrow().getSnapshot();
        assertThat(before.nodes()).hasSize(3);

        setScope(NodeVisibility.URL_ONLY);

        ResultActions list = saved().andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].snippet").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(wanderer.prague().getId()))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(2))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].nodeId").value(contains(unit.intValue())))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].label").value(CASTLE))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].sourceUrl").value(CASTLE))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].content").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].visibilityScope").value("URL_ONLY"))
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(3)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[*].from", everyItem(startsWith("tag:"))));
        assertThat(body(list)).doesNotContain(CARD_KEYWORD, TOPIC_KEYWORD, UNIT_KEYWORD, UNIT_TEXT, PRAGUE_TITLE, DEFAULT_TITLE);
        assertThat(rows.findById(id).orElseThrow().getSnapshot()).isEqualTo(before);

        setScope(NodeVisibility.CONTENT);

        saved().andExpect(jsonPath("$.data.mindmaps[0].title").value(PRAGUE_TITLE))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes", hasSize(3)))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[?(@.label == '" + UNIT_KEYWORD + "')].content")
                        .value(contains(UNIT_TEXT)));
    }

    @Test
    @DisplayName("URL만: 같은 URL 을 인용한 AI 노드는 한 번만 보이고, http(s) 가 아닌 인용 URL 은 빠진다")
    void urlOnlyDeduplicatesAndDropsUnsafeUrls() throws Exception {
        BookmarkSnapshot.Node first = new BookmarkSnapshot.Node(node(CASTLE), "AI 첫째", CASTLE, UNIT_TEXT, NodeVisibility.CONTENT);
        BookmarkSnapshot.Node second = new BookmarkSnapshot.Node(node(PRAGUE_EU), "AI 둘째", CASTLE, UNIT_TEXT, NodeVisibility.CONTENT);
        BookmarkSnapshot.Node unsafe = new BookmarkSnapshot.Node(node(PRAGUE_EU), "AI 셋째", "javascript:alert(1)", UNIT_TEXT,
                NodeVisibility.CONTENT);
        rows.saveAndFlush(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.NODE, "same-url", wanderer.id(),
                List.of(first.nodeId(), second.nodeId()), new BookmarkSnapshot(DEFAULT_TITLE, List.of(),
                        List.of(first, second, unsafe), List.of()), Instant.now()));

        ResultActions list = saved().andExpect(jsonPath("$.data.mindmaps[0].nodes[*].label").value(contains(CASTLE)));
        assertThat(body(list)).doesNotContain("javascript", UNIT_TEXT, "AI 첫째");
    }

    @Test
    @DisplayName("URL만: 두 노드에 모두 URL 이 있어도 저장한 노드 간 연결은 쓰지 않고 태그 → URL 만 온다")
    void urlOnlyDropsSavedStructure() throws Exception {
        Long castle = node(CASTLE);
        Long eu = node(PRAGUE_EU);
        rows.saveAndFlush(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.NODE, "structure", wanderer.id(),
                List.of(castle, eu), new BookmarkSnapshot(DEFAULT_TITLE, List.of(),
                        List.of(new BookmarkSnapshot.Node(castle, "AI 위", CASTLE, UNIT_TEXT, NodeVisibility.CONTENT),
                                new BookmarkSnapshot.Node(eu, "AI 아래", PRAGUE_EU, UNIT_TEXT, NodeVisibility.CONTENT)),
                        List.of(new BookmarkSnapshot.Edge("node:" + castle, "node:" + eu))), Instant.now()));

        saved().andExpect(jsonPath("$.data.mindmaps[0].nodes", hasSize(2)))
                // 프라하 세션 태그 3개 × 노드 2개
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(6)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[*].from", everyItem(startsWith("tag:"))));
    }

    @Test
    @DisplayName("URL만 → 내용까지: 제목 · 요약이 나오고 URL 노드는 저장한 그대로 내용까지 범위로 보인다")
    void urlOnlyToContentShowsTitle() throws Exception {
        saveCard(wanderer.prague());

        setScope(NodeVisibility.CONTENT);

        saved().andExpect(jsonPath("$.data.mindmaps[0].title").value(PRAGUE_TITLE))
                .andExpect(jsonPath("$.data.mindmaps[0].snippet").value(PRAGUE_SNIPPET))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].label").value(contains(CASTLE, PRAGUE_EU)))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].visibilityScope", everyItem(is("CONTENT"))));
    }

    @Test
    @DisplayName("옛 기본 제목(영문 · 한글)은 어떤 경우에도 나오지 않는다: 카드가 있으면 카드 제목, 없으면 null")
    void defaultTitlesNeverLeak() throws Exception {
        BookmarkSnapshot.Node stored = new BookmarkSnapshot.Node(node(CASTLE), CASTLE, CASTLE, null, NodeVisibility.URL_ONLY);
        BookmarkSnapshot.Tag interest = new BookmarkSnapshot.Tag(hashtagId(wanderer.member(), "카페투어"), wanderer.id(), "카페투어");
        Instant now = Instant.now();
        rows.save(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.NODE, "old-node", wanderer.id(),
                List.of(stored.nodeId()), new BookmarkSnapshot(OLD_TITLE, List.of(), List.of(stored), List.of()), now));
        // 관심사 태그만 고른 미니 마인드맵은 노드가 없어 이어진 세션이 없다
        rows.saveAndFlush(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.HASHTAG, "old-tag", wanderer.id(),
                List.of(interest.hashtagId()), new BookmarkSnapshot(OLD_TITLE, List.of(interest), List.of(), List.of()),
                now.plusSeconds(1)));

        ResultActions urlOnly = saved().andExpect(jsonPath("$.data.mindmaps", hasSize(2)))
                .andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("카페투어")))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(0))
                .andExpect(jsonPath("$.data.mindmaps[1].title").value(nullValue()));
        assertThat(body(urlOnly)).doesNotContain(OLD_TITLE);

        setScope(NodeVisibility.CONTENT);

        ResultActions content = saved().andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[1].title").value(PRAGUE_TITLE));
        assertThat(body(content)).doesNotContain(OLD_TITLE);
    }

    @Test
    @DisplayName("관심사 태그만 고른 저장은 이어진 세션이 없어 태그 공개 규칙을 따른다: 태그가 비공개가 되면 숨기고, 원본 보기도 닫힌다")
    void tagOnlyMiniFollowsTagVisibility() throws Exception {
        Long interest = hashtagId(wanderer.member(), "카페투어");
        Long id = readLong(save("HASHTAG", List.of(interest), "MINI_MINDMAP", null).andExpect(status().isCreated()),
                "$.data.bookmarkId");
        saved().andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(id))))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes", hasSize(0)));

        mvc.perform(patch("/community/me/hashtags/{id}/visibility", interest).with(as(wanderer.member()))
                .with(json(Map.of("isPublic", false)))).andExpect(okEnvelope());

        saved().andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        original(id).andExpect(jsonPath("$.data.sourceAvailable").value(false));
    }

    @Test
    @DisplayName("저장되는 스냅샷의 기본 제목은 한글이다 (URL만 카드 · 노드를 골라 저장)")
    void storedDefaultTitleIsKorean() throws Exception {
        Long card = saveCard(wanderer.prague());
        Long picked = saveNodes(List.of(node(WIEN)));

        assertThat(rows.findById(card).orElseThrow().getSnapshot().title()).isEqualTo(DEFAULT_TITLE);
        assertThat(rows.findById(picked).orElseThrow().getSnapshot().title()).isEqualTo(DEFAULT_TITLE);
    }

    // ---------- 세션 여러 개 ----------

    @Test
    @DisplayName("여러 세션: 세션 id · 제목 · 요약은 null, 작성자는 스냅샷 기준, 태그는 세션별 지금 태그, 노드 수는 보이는 노드, 좋아요는 합")
    void mixedSourcesShowTagsInsteadOfTitle() throws Exception {
        Long castle = node(CASTLE);
        Long wien = node(WIEN);
        Long id = saveNodes(List.of(castle, wien));
        setScope(NodeVisibility.CONTENT);
        like(member("other"), wanderer.prague());
        like(member("third"), wanderer.vienna());

        editSession(wanderer.vienna(), Map.of("tags", List.of("비엔나", "미술관")));

        Long vienna = hashtagId(wanderer.member(), "비엔나");
        Long museum = hashtagId(wanderer.member(), "미술관");
        saved().andExpect(jsonPath("$.data.mindmaps[0].bookmarkId").value(id))
                .andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].snippet").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.userId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.mindmaps[0].owner.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(
                        containsInAnyOrder("프라하", "카를교", "체코", "비엔나", "미술관")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(2))
                .andExpect(jsonPath("$.data.mindmaps[0].likeCount").value(2))
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(5)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[?(@.from == 'tag:" + museum + "')].to")
                        .value(contains("node:" + wien)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[?(@.from == 'tag:" + vienna + "')].to")
                        .value(contains("node:" + wien)));
    }

    @Test
    @DisplayName("여러 세션: 하나가 비공개가 돼도 여러 세션 항목으로 남고 그 세션의 노드 · 태그 · 다시 저장 대상 · 좋아요만 빠진다")
    void mixedSourcesHidePerNode() throws Exception {
        Long castle = node(CASTLE);
        Long wien = node(WIEN);
        saveNodes(List.of(castle, wien));
        setScope(NodeVisibility.CONTENT);
        like(member("other"), wanderer.vienna());

        editSession(wanderer.vienna(), Map.of("isPublic", false));

        saved().andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].nodeId").value(contains(castle.intValue())))
                .andExpect(jsonPath("$.data.mindmaps[0].targetIds").value(contains(castle.intValue())))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodeCount").value(1))
                .andExpect(jsonPath("$.data.mindmaps[0].likeCount").value(0));
    }

    @Test
    @DisplayName("여러 세션 + 내용까지 → URL만: 세션마다 URL 라벨만 남고 AI 글이 응답에 없다")
    void mixedSourcesRespectUrlOnly() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "프라하", CASTLE);
        analyse(wanderer.vienna(), "비엔나", WIEN);
        saveTags("프라하", "비엔나");

        setScope(NodeVisibility.URL_ONLY);

        ResultActions list = saved().andExpect(jsonPath("$.data.mindmaps", hasSize(1)))
                .andExpect(jsonPath("$.data.mindmaps[0].sessionId").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()));
        String response = body(list);
        assertThat(JsonPath.<List<String>>read(response, "$.data.mindmaps[0].nodes[*].label"))
                .contains(CASTLE, WIEN).doesNotContain(UNIT_KEYWORD);
        assertThat(response).doesNotContain(CARD_KEYWORD, TOPIC_KEYWORD, UNIT_KEYWORD, UNIT_TEXT);
    }

    // ---------- 원래 마인드맵 ----------

    @Test
    @DisplayName("원래 마인드맵: 내용까지 → URL만 뒤에도 같은 세션의 URL 노드가 강조된다")
    void originalHighlightsSameSessionAfterUrlOnly() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long id = saveCard(wanderer.prague());

        setScope(NodeVisibility.URL_ONLY);

        original(id).andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.mindmap.nodes[*].label", not(org.hamcrest.Matchers.hasItem(UNIT_KEYWORD))))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(containsInAnyOrder(ids(node(CASTLE), node(PRAGUE_EU)))));
        expectHighlightsAreGraphNodes(id);
    }

    @Test
    @DisplayName("원래 마인드맵: 태그로 고른 저장은 URL만이면 id 로 맞추고, 내용까지 → URL만 뒤에는 세션들의 URL 노드가 강조된다")
    void originalHighlightsTagPick() throws Exception {
        Long urlPick = saveTags("비엔나");
        original(urlPick).andExpect(jsonPath("$.data.highlightedNodeIds").value(
                containsInAnyOrder(ids(node(WIEN), node(BELVEDERE), node("https://www.cafecentral.wien/en/")))));

        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "프라하", CASTLE);
        analyse(wanderer.vienna(), "비엔나", WIEN);
        Long aiPick = saveTags("프라하", "비엔나");

        setScope(NodeVisibility.URL_ONLY);

        original(aiPick).andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(
                        containsInAnyOrder(ids(node(CASTLE), node(PRAGUE_EU), node(WIEN), node(BELVEDERE)))));
        expectHighlightsAreGraphNodes(aiPick);
    }

    @Test
    @DisplayName("원래 마인드맵: id 가 맞는 세션은 그 노드만, id 가 안 맞는 세션은 세션 전체를 강조한다")
    void originalFallsBackPerSession() throws Exception {
        Long wien = node(WIEN);
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long id = saveNodes(List.of(aiNode(UNIT_KEYWORD), wien));

        setScope(NodeVisibility.URL_ONLY);

        original(id).andExpect(jsonPath("$.data.highlightedNodeIds").value(
                containsInAnyOrder(ids(node(CASTLE), node(PRAGUE_EU), wien))));
    }

    @Test
    @DisplayName("원래 마인드맵: 일부 노드만 고른 저장은 id 가 맞으면 그 노드만 강조한다 (세션 전체로 넓히지 않는다)")
    void originalKeepsIdMatch() throws Exception {
        Long id = saveNodes(List.of(node(CASTLE)));

        original(id).andExpect(jsonPath("$.data.highlightedNodeIds").value(contains(ids(node(CASTLE)))));
    }

    @Test
    @DisplayName("원래 마인드맵: 재분석으로 AI 노드 id 가 바뀌어도 내용까지 그래프에서 같은 세션 노드를 강조한다")
    void originalAfterReanalysis() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "옛", CASTLE);
        Long id = readLong(save("NODE", List.of(aiNode("옛" + UNIT_KEYWORD)), "MINI_MINDMAP", null), "$.data.bookmarkId");

        replaceAnalysis(wanderer.prague(), "새", PRAGUE_EU);

        Long renewed = aiNode("새" + UNIT_KEYWORD);
        original(id).andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.mindmap.nodes[*].label").value(org.hamcrest.Matchers.hasItem("새" + UNIT_KEYWORD)))
                .andExpect(jsonPath("$.data.highlightedNodeIds", hasSize(3)))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(org.hamcrest.Matchers.hasItem(renewed.intValue())));
        expectHighlightsAreGraphNodes(id);
    }

    @Test
    @DisplayName("원래 마인드맵: 재분석으로 그 세션의 AI 노드가 없어지면 URL 그래프에서 같은 세션 노드를 강조한다")
    void originalAfterAnalysisEmptied() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long id = saveNodes(List.of(aiNode(UNIT_KEYWORD)));

        jdbc.update("update session_results set mindmap = JSON '{\"nodes\": [], \"edges\": []}' where session_id = ?",
                wanderer.prague().getId());
        cardSync.sync(wanderer.prague());

        original(id).andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.highlightedNodeIds", hasSize(2)))
                .andExpect(jsonPath("$.data.mindmap.nodes[*].sourceUrl").value(org.hamcrest.Matchers.hasItems(CASTLE, PRAGUE_EU)));
        expectHighlightsAreGraphNodes(id);
    }

    @Test
    @DisplayName("원래 마인드맵: 내용까지 → URL만 뒤에 원본을 비공개로 돌리면 강조 대신 sourceAvailable false 다")
    void originalStaysUnavailableAfterUrlOnlyAndPrivate() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long id = saveCard(wanderer.prague());
        setScope(NodeVisibility.URL_ONLY);

        editSession(wanderer.prague(), Map.of("isPublic", false));

        original(id).andExpect(jsonPath("$.data.sourceAvailable").value(false))
                .andExpect(jsonPath("$.data.mindmap").value(nullValue()))
                .andExpect(jsonPath("$.data.highlightedNodeIds", hasSize(0)));
    }

    // ---------- 묶음 이름 ----------

    private ResultActions saveBundleNamed(String name, String... hashtags) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String hashtag : hashtags) {
            ids.add(hashtagId(wanderer.member(), hashtag));
        }
        return save("HASHTAG", ids, "HASHTAG_BUNDLE", name);
    }

    @Test
    @DisplayName("묶음 이름: 앞뒤 공백을 지우고 저장하며 저장 응답에도 온다. 공백뿐이면 null 이다")
    void bundleNameIsTrimmed() throws Exception {
        saveBundleNamed("  동유럽 자료  ", "프라하").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("동유럽 자료"));
        saveBundleNamed("   ", "비엔나").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(nullValue()));
        saveBundleNamed(null, "야간열차").andExpect(status().isCreated());

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[0].name").value(nullValue()))
                .andExpect(jsonPath("$.data.bundles[1].name").value(nullValue()))
                .andExpect(jsonPath("$.data.bundles[2].name").value("동유럽 자료"));
    }

    @Test
    @DisplayName("묶음 이름: 공백을 지운 뒤 30자까지, 31자면 한국어 400 이고 저장되지 않는다")
    void bundleNameLength() throws Exception {
        saveBundleNamed("  " + "가".repeat(30) + "  ", "프라하").andExpect(status().isCreated());

        saveBundleNamed("가".repeat(31), "야간열차")
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "묶음 이름은 30자까지 입력할 수 있습니다."));

        mvc.perform(get(BUNDLES).with(as(me))).andExpect(jsonPath("$.data.bundles", hasSize(1)));
    }

    @Test
    @DisplayName("묶음 이름: 글자가 아닌 제어 문자가 있으면 400 이다 (DB 에 넣지 못하는 NUL 포함)")
    void bundleNameRejectsControlCharacters() throws Exception {
        saveBundleNamed("이름\u0000", "프라하")
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "묶음 이름에 쓸 수 없는 문자가 있습니다."));
        saveBundleNamed("줄\n바꿈", "프라하")
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "묶음 이름에 쓸 수 없는 문자가 있습니다."));
    }

    @Test
    @DisplayName("묶음 이름: 미니 마인드맵에 이름을 보내면 400, 비었거나 공백이면 없는 것으로 본다")
    void bundleNameOnlyForBundles() throws Exception {
        Long prague = hashtagId(wanderer.member(), "프라하");

        save("HASHTAG", List.of(prague), "MINI_MINDMAP", "이름")
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "이름은 해시태그 묶음에만 붙일 수 있습니다."));
        save("HASHTAG", List.of(prague), "MINI_MINDMAP", "  ").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(nullValue()));
    }

    @Test
    @DisplayName("같은 선택을 다시 저장하면 같은 행이다: 이름이 있으면 바꾸고, 없거나 비면 두고, 저장 시각 · 순서는 그대로다")
    void sameSelectionResave() throws Exception {
        Long id = readLong(saveBundleNamed("처음", "프라하"), "$.data.bookmarkId");
        Instant savedAt = rows.findById(id).orElseThrow().getSavedAt();
        Long other = readLong(saveBundleNamed(null, "비엔나"), "$.data.bookmarkId");

        saveBundleNamed("바꾼 이름", "프라하").andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.bookmarkId").value(id))
                .andExpect(jsonPath("$.data.name").value("바꾼 이름"));
        rows.flush();
        saveBundleNamed(null, "프라하").andExpect(jsonPath("$.data.name").value("바꾼 이름"));
        saveBundleNamed("  ", "프라하").andExpect(jsonPath("$.data.name").value("바꾼 이름"));

        assertThat(rows.findById(id).orElseThrow().getSavedAt()).isEqualTo(savedAt);
        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(other, id))))
                .andExpect(jsonPath("$.data.bundles[1].name").value("바꾼 이름"));
    }

    // ---------- 되돌리기 ----------

    private Map<String, Object> resaveBody(String response, String path) {
        Map<String, Object> item = JsonPath.read(response, path);
        Map<String, Object> body = new HashMap<>();
        for (String key : List.of("targetType", "targetIds", "saveType", "sourceMindmapId", "name")) {
            body.put(key, item.get(key));
        }
        return body;
    }

    @Test
    @DisplayName("되돌리기: 카드 북마크는 지운 뒤 세션 id 로 다시 켜면 새 행이 맨 위로 오고 카드 숫자가 돌아온다")
    void undoCardBookmark() throws Exception {
        Long first = saveCard(wanderer.prague());
        Long other = saveCard(wanderer.vienna());
        String response = body(saved().andExpect(jsonPath("$.data.mindmaps[1].cardBookmark").value(true)));
        Number sessionId = JsonPath.read(response, "$.data.mindmaps[1].sessionId");

        mvc.perform(delete(BOOKMARKS + "/{id}", first).with(as(me))).andExpect(okEnvelope());
        Long again = readLong(mvc.perform(patch("/community/mindmaps/{id}/bookmark", sessionId).with(as(me))
                        .with(json(Map.of("bookmarked", true)))).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bookmarkCount").value(1)), "$.data.bookmarkId");

        assertThat(again).isNotEqualTo(first);
        saved().andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(again, other))));
    }

    @Test
    @DisplayName("되돌리기: 태그로 고른 미니 마인드맵은 목록 값 그대로 POST 하면 새 행이 맨 위로 온다")
    void undoTagPick() throws Exception {
        Long first = saveTags("프라하", "비엔나");
        Long other = saveCard(wanderer.prague());
        String response = body(saved());
        assertThat(JsonPath.<Boolean>read(response, "$.data.mindmaps[1].cardBookmark")).isFalse();

        mvc.perform(delete(BOOKMARKS + "/{id}", first).with(as(me))).andExpect(okEnvelope());
        Long again = readLong(mvc.perform(post(BOOKMARKS).with(as(me))
                        .with(json(resaveBody(response, "$.data.mindmaps[1]")))).andExpect(status().isCreated()),
                "$.data.bookmarkId");

        assertThat(again).isNotEqualTo(first);
        saved().andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(again, other))));
    }

    @Test
    @DisplayName("되돌리기: 이름 붙인 묶음은 목록 값 그대로 POST 하면 이름까지 돌아오고 맨 위로 온다")
    void undoNamedBundle() throws Exception {
        Long first = readLong(saveBundleNamed("동유럽 자료", "프라하"), "$.data.bookmarkId");
        Long other = readLong(saveBundleNamed(null, "비엔나"), "$.data.bookmarkId");
        String response = body(mvc.perform(get(BUNDLES).with(as(me))));

        mvc.perform(delete(BOOKMARKS + "/{id}", first).with(as(me))).andExpect(okEnvelope());
        Long again = readLong(mvc.perform(post(BOOKMARKS).with(as(me))
                        .with(json(resaveBody(response, "$.data.bundles[1]")))).andExpect(status().isCreated()),
                "$.data.bookmarkId");

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(again, other))))
                .andExpect(jsonPath("$.data.bundles[0].name").value("동유럽 자료"));
    }

    @Test
    @DisplayName("되돌리기: 내용까지 → URL만 뒤에도 카드는 PATCH, 태그로 고른 저장은 POST 로 다시 저장되고 AI 글이 새 스냅샷에 없다")
    void undoAfterUrlOnly() throws Exception {
        setScope(NodeVisibility.CONTENT);
        analyse(wanderer.prague(), "", CASTLE);
        Long card = saveCard(wanderer.prague());
        Long pick = saveTags("체코");
        setScope(NodeVisibility.URL_ONLY);
        String response = body(saved());

        mvc.perform(delete(BOOKMARKS + "/{id}", pick).with(as(me))).andExpect(okEnvelope());
        Long newPick = readLong(mvc.perform(post(BOOKMARKS).with(as(me))
                        .with(json(resaveBody(response, "$.data.mindmaps[0]")))).andExpect(status().isCreated()),
                "$.data.bookmarkId");
        mvc.perform(delete(BOOKMARKS + "/{id}", card).with(as(me))).andExpect(okEnvelope());
        Long newCard = saveCard(wanderer.prague());

        saved().andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(newCard, newPick))));
        assertThat(rows.findById(newCard).orElseThrow().getSnapshot().toString()).doesNotContain(UNIT_KEYWORD, UNIT_TEXT);
        assertThat(rows.findById(newPick).orElseThrow().getSnapshot().toString()).doesNotContain(UNIT_KEYWORD, UNIT_TEXT);
    }

    @Test
    @DisplayName("되돌리기: 지금 숨겨진 대상은 targetIds 에서 빠져, 일부가 숨겨진 태그 · 노드 저장도 다시 저장된다")
    void hiddenTargetsAreNotResaved() throws Exception {
        Long prague = hashtagId(wanderer.member(), "프라하");
        Long vienna = hashtagId(wanderer.member(), "비엔나");
        Long tags = saveTags("프라하", "비엔나");
        Long bundle = readLong(saveBundleNamed("이름", "프라하", "비엔나"), "$.data.bookmarkId");
        mvc.perform(patch("/community/me/hashtags/{id}/visibility", vienna).with(as(wanderer.member()))
                .with(json(Map.of("isPublic", false)))).andExpect(okEnvelope());

        String minis = body(saved().andExpect(jsonPath("$.data.mindmaps[0].targetIds").value(contains(prague.intValue()))));
        String bundles = body(mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[0].targetIds").value(contains(prague.intValue()))));

        mvc.perform(delete(BOOKMARKS + "/{id}", tags).with(as(me))).andExpect(okEnvelope());
        mvc.perform(delete(BOOKMARKS + "/{id}", bundle).with(as(me))).andExpect(okEnvelope());
        mvc.perform(post(BOOKMARKS).with(as(me)).with(json(resaveBody(minis, "$.data.mindmaps[0]"))))
                .andExpect(status().isCreated());
        mvc.perform(post(BOOKMARKS).with(as(me)).with(json(resaveBody(bundles, "$.data.bundles[0]"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("이름"));
    }

    @Test
    @DisplayName("POST 로 저장한 미니 마인드맵은 세션이 하나여도 카드 숫자에 들어가지 않고, 카드 북마크와 별개 행이다")
    void postedMiniDoesNotCountOnCard() throws Exception {
        Long picked = saveNodes(List.of(node(CASTLE), node(PRAGUE_EU)));

        String cards = body(mvc.perform(get("/community/profiles/{id}/mindmaps", wanderer.id()).with(as(me))));
        List<Number> count = JsonPath.read(cards, "$.data.mindmaps[?(@.mindmapId == " + wanderer.prague().getId() + ")].bookmarkCount");
        List<Object> bookmarkId = JsonPath.read(cards, "$.data.mindmaps[?(@.mindmapId == " + wanderer.prague().getId() + ")].bookmarkId");
        assertThat(count.get(0).intValue()).isZero();
        assertThat(bookmarkId.get(0)).isNull();

        assertThat(saveCard(wanderer.prague())).isNotEqualTo(picked);
        saved().andExpect(jsonPath("$.data.mindmaps", hasSize(2)));
    }

    // ---------- 쿼리 수 ----------

    private long listStatements(int expected) throws Exception {
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        saved().andExpect(jsonPath("$.data.mindmaps", hasSize(expected)));
        return statistics.getPrepareStatementCount();
    }

    @Test
    @DisplayName("미니 마인드맵 목록은 항목 수 · 작성자 수가 늘어도 쿼리 수가 같다")
    void listHasNoNPlusOne() throws Exception {
        saveCard(wanderer.prague());
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            long one = listStatements(1);
            saveCard(wanderer.vienna());
            saveCard(wanderer.nightjet());
            saveCard(wanderer.cafe());
            saveNodes(List.of(node(CASTLE), node(WIEN)));
            for (int i = 0; i < 3; i++) {
                Member author = member("author" + i);
                BrowsingSession session = session(author, "글 " + i, "2026-04-15T10:00:00", "2026-04-15T11:00:00", 1, false, true,
                        List.of("태그" + i), List.of(new Source("글", "https://example.com/" + i, "발췌")), "본문");
                saveCard(session);
            }
            assertThat(listStatements(8)).isEqualTo(one);
        } finally {
            statistics.setStatisticsEnabled(enabled);
        }
    }
}
