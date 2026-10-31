package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.api.SelectionKey;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.card.CommunityCardBackfill;
import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.community.card.CommunityCardRow;
import com.justinneed.community.catalog.CommunityHashtag;
import com.justinneed.community.catalog.CommunityHashtagRepository;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.graph.CommunityNode;
import com.justinneed.community.graph.CommunityNodeRepository;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** community_cards 와 그래프가 읽는 행은 세션이 바뀌는 쓰기 트랜잭션에서 맞춰지고, CommunityCardReader 는 그 행을 읽는다. */
@DisplayName("커뮤니티 카드")
class CommunityCardScenarioTest extends ScenarioTestSupport {

    private static final String LINK = "https://x.example.com/y";
    private static final String MINDMAP = """
            {"structureSource": "llm",
             "nodes": [
               {"id": "root:1", "type": "root", "keyword": "로마", "score": 1.0, "childCount": 1},
               {"id": "topic:0", "type": "topic", "keyword": "콜로세움", "score": 0.9, "childCount": 1},
               {"id": "https://x.example.com/y#pid1", "type": "unit", "keyword": "입장권", "score": 0.5, "childCount": 0}],
             "edges": [
               {"from": "root:1", "to": "topic:0", "type": "hierarchy", "primary": true},
               {"from": "topic:0", "to": "https://x.example.com/y#pid1", "type": "hierarchy", "primary": true}]}
            """;
    private static final String RESULT = """
            {"modelSource": "global", "scoreMethod": "C", "state": {"unitFeatures": []},
             "mindmap": %s,
             "essay": {"source": "llm", "title": "다시 분석한 제목", "sections": [{"heading": "소제목", "body": "분석 본문"}]},
             "selected": [{"unitKey": "https://x.example.com/y#pid1", "pageUrl": "https://x.example.com/y", "pageTitle": "글",
                           "text": "원문", "score": 0.5}],
             "analysis": {"units": [], "summary": {}}, "pages": []}
            """.formatted(MINDMAP);

    @Autowired private CommunityCardRepository cards;
    @Autowired private CommunityCardBackfill backfill;
    @Autowired private CommunityHashtagRepository hashtags;
    @Autowired private CommunityNodeRepository nodes;
    @Autowired private SessionResultRepository results;
    @Autowired private SessionRawPayloadRepository rawPayloads;
    @Autowired private AiInternalToken internalToken;
    @Autowired private JdbcTemplate jdbc;

    private Member me;
    private Member viewer;
    private BrowsingSession rome;

    @BeforeEach
    void seed() {
        me = member("sprout");
        viewer = member("viewer");
        rome = session(me, "로마 동선", "2026-04-26T21:00:00", "2026-04-26T22:00:00", 2, false, false,
                List.of("로마", "콜로세움"),
                List.of(new Source("글", LINK, "발췌"), new Source("", "https://x.example.com/z", "발췌"),
                        new Source("중복", LINK, "발췌"), new Source("위험", "javascript:alert(1)", "발췌")),
                "## 소제목\n첫 **문단**\n\n둘째 문단");
    }

    private void patchSession(Map<String, Object> body) throws Exception {
        mvc.perform(patch("/sessions/{id}", rome.getId()).with(as(me)).with(json(body))).andExpect(okEnvelope());
    }

    private CommunityCardRow card() {
        return cards.findById(rome.getId()).orElseThrow();
    }

    private void setResult(String mindmap) {
        SessionResult result = new SessionResult(rome.getId(), me.getId());
        results.saveAndFlush(result);
        jdbc.update("update session_results set mindmap = JSON '" + mindmap + "' where session_id = ?", rome.getId());
    }

    private void showContent() {
        CommunityProfile profile = communityProfiles.findById(me.getId()).orElseGet(() -> new CommunityProfile(me.getId()));
        profile.changeVisibility(NodeVisibility.CONTENT);
        communityProfiles.saveAndFlush(profile);
    }

    private List<String> nodeKeys() {
        return nodes.findBySessionId(rome.getId()).stream().map(CommunityNode::getSourceKey).toList();
    }

    @Test
    @DisplayName("공개로 바꾸면 카드가 생기고, 비공개로 바꾸면 사라진다. 노드 행은 남아 다시 공개해도 id 가 같다")
    void publicToggle() throws Exception {
        assertThat(cards.findById(rome.getId())).isEmpty();
        assertThat(nodeKeys()).isEmpty();

        patchSession(Map.of("isPublic", true));

        CommunityCardRow card = card();
        assertThat(card.getOwnerId()).isEqualTo(me.getId());
        assertThat(card.getTitle()).isEqualTo("로마 동선");
        assertThat(card.getSnippet()).isEqualTo("첫 문단");
        assertThat(card.getHashtags()).containsExactly("로마", "콜로세움");
        assertThat(card.getUrls()).containsExactly(new CommunityCard.RelatedUrl("글", LINK),
                new CommunityCard.RelatedUrl(null, "https://x.example.com/z"));
        assertThat(card.getUrlCount()).isEqualTo(2);
        assertThat(card.getAiNodeCount()).isZero();
        assertThat(card.getLastActivityAt()).isEqualTo(LocalDateTime.parse("2026-04-26T22:00:00"));
        var ids = nodes.findBySessionId(rome.getId()).stream().map(CommunityNode::getId).toList();
        assertThat(ids).hasSize(2);

        patchSession(Map.of("isPublic", false));
        assertThat(cards.findById(rome.getId())).isEmpty();
        assertThat(nodes.findBySessionId(rome.getId())).extracting(CommunityNode::getId).isEqualTo(ids);

        patchSession(Map.of("isPublic", true));
        assertThat(nodes.findBySessionId(rome.getId())).extracting(CommunityNode::getId).isEqualTo(ids);
    }

    @Test
    @DisplayName("공개 세션의 제목 · 태그 · 본문을 고치면 카드도 바뀐다")
    void editTitleTagsMarkdown() throws Exception {
        patchSession(Map.of("isPublic", true));

        patchSession(Map.of("title", "새 제목"));
        assertThat(card().getTitle()).isEqualTo("새 제목");

        patchSession(Map.of("tags", List.of("피렌체")));
        assertThat(card().getHashtags()).containsExactly("피렌체");
        assertThat(hashtags.findByOwnerIdOrderByIdAsc(me.getId())).extracting(CommunityHashtag::getName).contains("피렌체");

        patchSession(Map.of("editedMarkdown", "고친 본문"));
        assertThat(card().getSnippet()).isEqualTo("고친 본문");
    }

    @Test
    @DisplayName("즐겨찾기만 바꾸면 카드를 다시 쓰지 않는다")
    void favoriteDoesNotTouchCard() throws Exception {
        patchSession(Map.of("isPublic", true));
        cards.delete(card());
        cards.flush();

        patchSession(Map.of("isFavorite", true));

        assertThat(cards.findById(rome.getId())).isEmpty();
    }

    @Test
    @DisplayName("삭제하면 카드가 사라진다")
    void deleteRemovesCard() throws Exception {
        patchSession(Map.of("isPublic", true));

        mvc.perform(delete("/sessions/{id}", rome.getId()).with(as(me))).andExpect(okEnvelope());

        assertThat(cards.findById(rome.getId())).isEmpty();
    }

    @Test
    @DisplayName("핀을 꽂으면 공개가 되어 카드가 생기고, 빼면 비공개가 되어 사라진다")
    void pinAndUnpin() throws Exception {
        mvc.perform(post("/community/me/shared-sessions").with(as(me)).with(json(Map.of("sessionId", rome.getId()))))
                .andExpect(status().is2xxSuccessful());
        assertThat(cards.findById(rome.getId())).isPresent();

        mvc.perform(delete("/community/me/shared-sessions/{id}", rome.getId()).with(as(me))).andExpect(okEnvelope());
        assertThat(cards.findById(rome.getId())).isEmpty();
    }

    @Test
    @DisplayName("공개 세션의 재분석: 실패 세션을 다시 돌리면 카드가 없고, AI 결과가 저장되면 AI 노드까지 카드에 반영된다")
    void reanalysisAndAiResult() throws Exception {
        patchSession(Map.of("isPublic", true));
        ReflectionTestUtils.setField(rome, "status", SessionStatus.FAILED);
        rawPayloads.saveAndFlush(new SessionRawPayload(rome.getId(), me.getId(),
                Map.of("kind", "rbc-session", "pages", List.of()), LocalDateTime.now()));

        mvc.perform(post("/sessions/{id}/reanalyze", rome.getId()).with(as(me))).andExpect(okEnvelope());
        assertThat(cards.findById(rome.getId())).isEmpty();

        ReflectionTestUtils.setField(rome, "status", SessionStatus.ANALYZING);
        sessions.saveAndFlush(rome);
        mvc.perform(post("/internal/ai/sessions/{id}/result", rome.getId())
                        .header(AiInternalToken.HEADER, internalToken.value())
                        .contentType(MediaType.APPLICATION_JSON).content(RESULT))
                .andExpect(status().is2xxSuccessful());

        CommunityCardRow card = card();
        assertThat(card.getTitle()).isEqualTo("다시 분석한 제목");
        assertThat(card.getSnippet()).isEqualTo("분석 본문");
        assertThat(card.getAiNodeCount()).isEqualTo(3);
        assertThat(card.getHashtags()).isEqualTo(rome.getTags());
        assertThat(nodeKeys()).contains(SelectionKey.of("ai:root:1"), SelectionKey.of("ai:" + LINK + "#pid1"),
                SelectionKey.of(LINK));
    }

    @Test
    @DisplayName("비공개 세션의 AI 결과도 태그 행은 만든다 (프로필 수정 목록). 카드는 없다")
    void privateAiResultCreatesTagsOnly() throws Exception {
        assertThat(hashtags.findByOwnerIdOrderByIdAsc(me.getId())).extracting(CommunityHashtag::getName)
                .contains("로마", "콜로세움");
        assertThat(cards.findById(rome.getId())).isEmpty();
        assertThat(nodeKeys()).isEmpty();
        mvc.perform(get("/community/me/hashtags").with(as(me))).andExpect(okEnvelope());
    }

    @Test
    @DisplayName("탈퇴하면 그 회원의 카드가 사라진다")
    void withdrawRemovesCards() throws Exception {
        patchSession(Map.of("isPublic", true));

        mvc.perform(delete("/settings/account").with(as(me)).with(json(Map.of("confirmText", "WITHDRAW"))))
                .andExpect(status().isOk());

        assertThat(cards.existsById(rome.getId())).isFalse();
    }

    @Test
    @DisplayName("백필: 공개 세션의 카드 · 노드 · 태그를 채우고, 비공개 · 삭제 · 탈퇴 세션은 건너뛰며, 두 번 돌려도 그대로다")
    void backfillIsIdempotent() throws Exception {
        rome.update(null, null, true, null, null);
        BrowsingSession legacyPrivate = unsynced(me, false, "비공개태그");
        BrowsingSession legacyDeleted = unsynced(me, true, "삭제태그");
        legacyDeleted.delete();
        Member gone = member("gone");
        BrowsingSession withdrawn = unsynced(gone, true, "탈퇴태그");
        gone.withdraw(null, LocalDateTime.now());
        members.saveAndFlush(gone);
        CommunityProfile profile = new CommunityProfile(viewer.getId());
        profile.replaceInterests(List.of("관심"));
        communityProfiles.saveAndFlush(profile);
        sessions.flush();
        // 예전 조회가 만들어 둔 URL 노드는 id 가 유지돼야 한다
        Long legacyNode = nodes.saveAndFlush(new CommunityNode(me.getId(), rome.getId(), SelectionKey.of(LINK))).getId();

        backfill.run(null);

        assertThat(card().getTitle()).isEqualTo("로마 동선");
        assertThat(cards.findById(legacyPrivate.getId())).isEmpty();
        assertThat(cards.findById(legacyDeleted.getId())).isEmpty();
        assertThat(cards.findById(withdrawn.getId())).isEmpty();
        assertThat(nodes.findBySessionId(rome.getId())).extracting(CommunityNode::getId).contains(legacyNode).hasSize(2);
        assertThat(hashtags.findByOwnerIdOrderByIdAsc(me.getId())).extracting(CommunityHashtag::getName)
                .contains("로마", "비공개태그").doesNotContain("삭제태그");
        assertThat(hashtags.findByOwnerIdOrderByIdAsc(viewer.getId())).extracting(CommunityHashtag::getName).contains("관심");
        assertThat(hashtags.findByOwnerIdOrderByIdAsc(gone.getId())).isEmpty();

        var tagRows = hashtags.count();
        var nodeRows = nodes.count();
        var cardRows = cards.count();
        var cardUpdatedAt = card().getUpdatedAt();
        backfill.run(null);

        assertThat(hashtags.count()).isEqualTo(tagRows);
        assertThat(nodes.count()).isEqualTo(nodeRows);
        assertThat(cards.count()).isEqualTo(cardRows);
        assertThat(card().getUpdatedAt()).isEqualTo(cardUpdatedAt);
    }

    private BrowsingSession unsynced(Member owner, boolean isPublic, String tag) {
        BrowsingSession session = new BrowsingSession(owner.getId(), "옛 세션", LocalDateTime.now());
        session.complete(LocalDateTime.now(), 1);
        session.update(null, null, isPublic, false, List.of(tag));
        session.replaceSources(List.of(new Source("글", "https://old.example.com/" + tag, "발췌")));
        return sessions.saveAndFlush(session);
    }
}
