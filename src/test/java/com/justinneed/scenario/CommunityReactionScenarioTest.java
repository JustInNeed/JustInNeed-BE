package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.graph.CommunityNode;
import com.justinneed.community.graph.CommunityNodeRepository;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/** 카드(세션) 단위 좋아요 · 나도 탐색중 · 북마크. 숫자는 카드 응답과 PATCH 응답이 같은 기준이다. */
@DisplayName("카드 반응")
class CommunityReactionScenarioTest extends ScenarioTestSupport {

    private static final String LIKE = "/community/mindmaps/{id}/like";
    private static final String EXPLORING = "/community/mindmaps/{id}/exploring";
    private static final String BOOKMARK = "/community/mindmaps/{id}/bookmark";
    private static final String SAVED = "/community/me/bookmarks/mindmaps";
    private static final String MISSING = "요청한 정보를 찾을 수 없습니다.";
    // 경로 변수 검증이 있는 커뮤니티 API 는 본문 오류도 이 문구로 나온다 (프로필 좋아요 · 팔로우와 같다)
    private static final String INVALID = "요청 값이 올바르지 않습니다.";
    private static final String MINDMAP = """
            {"structureSource": "llm",
             "nodes": [
               {"id": "root:1", "type": "root", "keyword": "프라하", "score": 1.0, "childCount": 1},
               {"id": "topic:0", "type": "topic", "keyword": "카를교", "score": 0.9, "childCount": 0}],
             "edges": [{"from": "root:1", "to": "topic:0", "type": "hierarchy", "primary": true}]}
            """;

    @Autowired private CommunityCardReader reader;
    @Autowired private CommunityNodeRepository nodes;
    @Autowired private SessionResultRepository results;
    @Autowired private BookmarkQueryService bookmarkQueries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;

    private Member me;
    private Member other;
    private Wanderer wanderer;
    private BrowsingSession prague;

    @BeforeEach
    void seed() {
        me = member("sprout");
        other = member("other");
        wanderer = seedWanderer();
        prague = wanderer.prague();
    }

    private ResultActions like(Member actor, BrowsingSession card, boolean liked) throws Exception {
        return mvc.perform(patch(LIKE, card.getId()).with(as(actor)).with(json(Map.of("liked", liked))));
    }

    private ResultActions exploring(Member actor, BrowsingSession card, boolean exploring) throws Exception {
        return mvc.perform(patch(EXPLORING, card.getId()).with(as(actor)).with(json(Map.of("exploring", exploring))));
    }

    private ResultActions bookmark(Member actor, BrowsingSession card, boolean bookmarked) throws Exception {
        return mvc.perform(patch(BOOKMARK, card.getId()).with(as(actor)).with(json(Map.of("bookmarked", bookmarked))));
    }

    private ResultActions saved(Member actor) throws Exception {
        return mvc.perform(get(SAVED).with(as(actor)));
    }

    private CommunityCard card(BrowsingSession session, Member viewer) {
        return reader.card(session.getId(), viewer.getId()).orElseThrow();
    }

    private void setPublic(BrowsingSession session, boolean isPublic) throws Exception {
        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(wanderer.member()))
                .with(json(Map.of("isPublic", isPublic)))).andExpect(okEnvelope());
    }

    private void showContent() {
        CommunityProfile profile = communityProfiles.findById(wanderer.id()).orElseThrow();
        profile.changeVisibility(NodeVisibility.CONTENT);
        communityProfiles.saveAndFlush(profile);
    }

    private void analyse(BrowsingSession session) {
        results.saveAndFlush(new SessionResult(session.getId(), session.getUserId()));
        jdbc.update("update session_results set mindmap = JSON '" + MINDMAP + "' where session_id = ?", session.getId());
        cardSync.sync(session);
    }

    private void withdraw(Member member) {
        members.findById(member.getId()).orElseThrow().withdraw(null, LocalDateTime.now());
        entityManager.flush();
    }

    private List<String> activityTags(Member actor, String kind) {
        return jdbc.queryForList("""
                select h.name from community_activities a join community_hashtags h on h.id = a.tag_id
                where a.actor_id = ? and a.kind = ? order by h.name
                """, String.class, actor.getId(), kind);
    }

    private int rows(String table, Member actor) {
        return jdbc.queryForObject("select count(*) from " + table + " where actor_id = ?", Integer.class, actor.getId());
    }

    // ---------- 좋아요 ----------

    @Test
    @DisplayName("좋아요: 수와 내 상태가 바뀌고 같은 값을 다시 보내도 두 번 세지 않는다")
    void like() throws Exception {
        like(me, prague, true).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.likeCount").value(1))
                .andExpect(jsonPath("$.data.likedByMe").value(true));
        like(me, prague, true).andExpect(okEnvelope()).andExpect(jsonPath("$.data.likeCount").value(1));
        like(other, prague, true).andExpect(jsonPath("$.data.likeCount").value(2));

        assertThat(card(prague, me).likeCount()).isEqualTo(2);
        assertThat(card(prague, me).likedByMe()).isTrue();
        assertThat(card(wanderer.vienna(), me).likeCount()).isZero();

        like(me, prague, false).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.likeCount").value(1))
                .andExpect(jsonPath("$.data.likedByMe").value(false));
        like(me, prague, false).andExpect(okEnvelope()).andExpect(jsonPath("$.data.likeCount").value(1));
        assertThat(card(prague, me).likedByMe()).isFalse();
    }

    @Test
    @DisplayName("좋아요: 내 카드에는 누를 수 없다 (프로필 좋아요와 같다). 다른 사람 눈의 숫자는 그대로다")
    void likeOwnCard() throws Exception {
        like(wanderer.member(), prague, true)
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "내 프로필과 마인드맵에는 좋아요를 누를 수 없습니다."));

        assertThat(card(prague, me).likeCount()).isZero();
    }

    // ---------- 공개 조건 ----------

    @Test
    @DisplayName("공개 카드가 아니면 세 API 모두 어느 값을 보내도 404 다")
    void notPublicCard() throws Exception {
        BrowsingSession failed = sessionWithStatus(wanderer.member(), "분석 실패", "2026-04-20T10:00:00",
                "2026-04-20T11:00:00", SessionStatus.FAILED, List.of("프라하"));
        for (BrowsingSession hidden : List.of(wanderer.slow(), failed)) {
            for (boolean value : List.of(true, false)) {
                like(me, hidden, value).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MISSING));
                exploring(me, hidden, value).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MISSING));
                bookmark(me, hidden, value).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MISSING));
            }
        }
        // 작성자 본인이어도 공개 카드가 아니면 없는 카드다
        like(wanderer.member(), wanderer.slow(), true).andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MISSING));
        mvc.perform(patch(LIKE, 999_999L).with(as(me)).with(json(Map.of("liked", true))))
                .andExpect(errorEnvelope(HttpStatus.NOT_FOUND, MISSING));
        assertThat(jdbc.queryForObject("select count(*) from community_bookmarks", Integer.class)).isZero();
        assertThat(activityTags(me, "EXPLORE")).isEmpty();
    }

    @Test
    @DisplayName("삭제한 세션 · 탈퇴한 작성자의 카드는 404 다")
    void deletedOrWithdrawn() throws Exception {
        mvc.perform(delete("/sessions/{id}", prague.getId()).with(as(wanderer.member()))).andExpect(okEnvelope());
        like(me, prague, true).andExpect(status().isNotFound());

        cardSync.removeOwner(wanderer.id());
        withdraw(wanderer.member());
        exploring(me, wanderer.vienna(), true).andExpect(status().isNotFound());
        bookmark(me, wanderer.vienna(), true).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("카드가 비공개가 돼도 행은 남고, 다시 공개하면 숫자 · 내 상태 · 북마크가 돌아온다")
    void cardLeavingPublic() throws Exception {
        like(me, prague, true);
        exploring(me, prague, true);
        Long bookmarkId = readLong(bookmark(me, prague, true), "$.data.bookmarkId");

        setPublic(prague, false);

        assertThat(reader.card(prague.getId(), me.getId())).isEmpty();
        saved(me).andExpect(jsonPath("$.data.mindmaps").value(hasSize(0)));
        like(me, prague, false).andExpect(status().isNotFound());
        assertThat(rows("community_mindmap_likes", me)).isEqualTo(1);
        assertThat(rows("community_mindmap_explorations", me)).isEqualTo(1);

        setPublic(prague, true);

        CommunityCard back = card(prague, me);
        assertThat(back.likeCount()).isEqualTo(1);
        assertThat(back.likedByMe()).isTrue();
        assertThat(back.exploringCount()).isEqualTo(1);
        assertThat(back.exploringByMe()).isTrue();
        assertThat(back.bookmarkCount()).isEqualTo(1);
        assertThat(back.bookmarkId()).isEqualTo(bookmarkId);
        saved(me).andExpect(jsonPath("$.data.mindmaps[0].bookmarkId").value(bookmarkId.intValue()));
    }

    // ---------- 나도 탐색중 ----------

    @Test
    @DisplayName("나도 탐색중: 수와 내 상태가 바뀌고, 취소했다 다시 눌러도 같은 날 활동은 한 번이다")
    void exploring() throws Exception {
        exploring(me, prague, true).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.exploringCount").value(1))
                .andExpect(jsonPath("$.data.exploringByMe").value(true));
        exploring(me, prague, true).andExpect(jsonPath("$.data.exploringCount").value(1));
        exploring(other, prague, true).andExpect(jsonPath("$.data.exploringCount").value(2));

        assertThat(card(prague, me).exploringCount()).isEqualTo(2);
        assertThat(card(prague, me).exploringByMe()).isTrue();

        exploring(me, prague, false).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.exploringCount").value(1))
                .andExpect(jsonPath("$.data.exploringByMe").value(false));
        exploring(me, prague, true);

        assertThat(activityTags(me, "EXPLORE")).containsExactlyInAnyOrder("프라하", "카를교", "체코");
    }

    @Test
    @DisplayName("나도 탐색중: 내 카드도 누를 수 있고, 활동은 공개 태그만 남긴다. 떠오르는 해시태그에 잡힌다")
    void exploringRecordsPublicTags() throws Exception {
        exploring(wanderer.member(), prague, true).andExpect(okEnvelope());
        exploring(me, wanderer.cafe(), true).andExpect(okEnvelope());

        assertThat(activityTags(me, "EXPLORE")).containsExactly("비엔나");
        mvc.perform(get("/community/recommendations/hashtags/trending").param("period", "TODAY").with(as(me)))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(hasItem("비엔나")))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(hasItem("체코")))
                .andExpect(jsonPath("$.data.hashtags[*].name").value(not(hasItem("카페"))));
    }

    @Test
    @DisplayName("기존 노드 · 해시태그 나도 탐색중은 그대로 동작하고 카드 숫자와 섞이지 않는다")
    void nodeAndHashtagExploringKeepWorking() throws Exception {
        Long hashtag = hashtagId(wanderer.member(), "프라하");
        mvc.perform(patch("/community/exploring").with(as(me))
                        .with(json(Map.of("targetType", "HASHTAG", "targetId", hashtag, "exploring", true))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.exploring").value(true))
                .andExpect(jsonPath("$.data.exploringCount").value(1));

        exploring(me, prague, true).andExpect(jsonPath("$.data.exploringCount").value(1));

        mvc.perform(get("/community/exploring").param("targetType", "HASHTAG").param("targetId", hashtag.toString())
                        .with(as(me)))
                .andExpect(jsonPath("$.data.exploringCount").value(1));
        assertThat(card(wanderer.nightjet(), me).exploringCount()).isZero();
        mvc.perform(get("/community/exploring").param("targetType", "CARD").param("targetId", "1").with(as(me)))
                .andExpect(status().isBadRequest());
    }

    // ---------- 북마크 ----------

    @Test
    @DisplayName("북마크: URL만 공개 카드는 그 카드의 URL 노드만 저장하고 제목 · 내용을 담지 않는다")
    void bookmarkUrlOnly() throws Exception {
        Long id = readLong(bookmark(me, prague, true).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bookmarkCount").value(1)), "$.data.bookmarkId");

        saved(me).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps").value(hasSize(1)))
                .andExpect(jsonPath("$.data.mindmaps[0].bookmarkId").value(id.intValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].sourceMindmapId").value(wanderer.id().intValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].label").value(contains(
                        "https://www.hrad.cz/en/prague-castle-for-visitors", "https://www.prague.eu/en")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].content").value(nullValue()))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].visibilityScope").value("URL_ONLY"))
                .andExpect(jsonPath("$.data.mindmaps[0].edges").value(hasSize(6)));
        assertThat(card(prague, me).bookmarkId()).isEqualTo(id);
        assertThat(card(prague, me).bookmarkCount()).isEqualTo(1);
        assertThat(card(prague, other).bookmarkId()).isNull();
        assertThat(activityTags(me, "BOOKMARK")).containsExactlyInAnyOrder("프라하", "카를교", "체코");
        assertThat(bookmarkQueries.recentHashtags(me.getId())).containsExactly("프라하", "카를교", "체코");
    }

    @Test
    @DisplayName("북마크: 내용까지 공개 카드는 AI 노드 · 연결선과 카드 제목을 저장한다")
    void bookmarkContent() throws Exception {
        showContent();
        analyse(prague);

        bookmark(me, prague, true).andExpect(okEnvelope());

        var keyword = saved(me).andExpect(jsonPath("$.data.mindmaps[0].title").value("프라하 1박 일정 짜기"))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].label").value(contains("프라하", "카를교")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].visibilityScope").value("CONTENT"));
        // 태그 3개 → 뿌리, 뿌리 → 주제
        keyword.andExpect(jsonPath("$.data.mindmaps[0].edges").value(hasSize(4)));
        Long bookmarkId = readLong(keyword, "$.data.mindmaps[0].bookmarkId");
        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(hasSize(2)));
    }

    @Test
    @DisplayName("북마크: 내용까지 공개인데 AI 결과가 없으면 URL 노드를 제목 · 발췌와 함께 저장한다")
    void bookmarkContentWithoutAnalysis() throws Exception {
        showContent();

        bookmark(me, prague, true).andExpect(okEnvelope());

        saved(me).andExpect(jsonPath("$.data.mindmaps[0].nodes[*].label").value(contains("프라하 성", "프라하 관광청")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].content").value("성 내부는 통합권으로 본다."));
    }

    @Test
    @DisplayName("북마크: 같은 작성자의 다른 카드 노드는 담지 않고, 비공개 태그도 담지 않는다")
    void bookmarkOnlyThisCard() throws Exception {
        bookmark(me, wanderer.nightjet(), true).andExpect(okEnvelope());
        bookmark(me, wanderer.cafe(), true).andExpect(okEnvelope());

        saved(me).andExpect(jsonPath("$.data.mindmaps").value(hasSize(2)))
                .andExpect(jsonPath("$.data.mindmaps[1].nodes[*].sourceUrl").value(contains(
                        "https://www.nightjet.com/en", "https://www.cd.cz/en/")))
                .andExpect(jsonPath("$.data.mindmaps[1].hashtags[*].name").value(contains("야간열차", "프라하")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(contains(
                        "https://www.cafecentral.wien/en/")))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("비엔나")));
    }

    @Test
    @DisplayName("북마크: 같은 값을 다시 보내면 같은 id, false 는 지운다. 없는 북마크를 지워도 200 이다")
    void bookmarkIdempotent() throws Exception {
        Long id = readLong(bookmark(me, prague, true), "$.data.bookmarkId");
        bookmark(me, prague, true).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bookmarkId").value(id.intValue()))
                .andExpect(jsonPath("$.data.bookmarkCount").value(1));
        bookmark(other, prague, true).andExpect(jsonPath("$.data.bookmarkCount").value(2));
        assertThat(jdbc.queryForObject("select count(*) from community_bookmarks where user_id = ?",
                Integer.class, me.getId())).isEqualTo(1);

        bookmark(me, prague, false).andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bookmarkId").value(nullValue()))
                .andExpect(jsonPath("$.data.bookmarkCount").value(1));
        bookmark(me, prague, false).andExpect(okEnvelope()).andExpect(jsonPath("$.data.bookmarkCount").value(1));
        saved(me).andExpect(jsonPath("$.data.mindmaps").value(hasSize(0)));

        Long again = readLong(bookmark(me, prague, true), "$.data.bookmarkId");
        assertThat(again).isNotEqualTo(id);
        assertThat(activityTags(me, "BOOKMARK")).hasSize(3);
    }

    @Test
    @DisplayName("북마크: 북마크 화면에서 지우면 카드 숫자도 내려가고, 노드 저장은 카드 북마크로 세지 않는다")
    void bookmarkOtherPaths() throws Exception {
        Long id = readLong(bookmark(me, prague, true), "$.data.bookmarkId");
        mvc.perform(delete("/community/bookmarks/{id}", id).with(as(me))).andExpect(okEnvelope());

        assertThat(card(prague, me).bookmarkCount()).isZero();
        assertThat(card(prague, me).bookmarkId()).isNull();

        Long node = nodes.findBySessionId(prague.getId()).stream().map(CommunityNode::getId).findFirst().orElseThrow();
        mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of("targetType", "NODE",
                        "targetIds", List.of(node), "saveType", "MINI_MINDMAP", "sourceMindmapId", wanderer.id()))))
                .andExpect(status().isCreated());
        assertThat(card(prague, me).bookmarkCount()).isZero();
    }

    @Test
    @DisplayName("북마크: 원본 세션을 지우면 숨겨지고, 비공개 태그로 돌린 태그만 빠진다")
    void bookmarkFollowsSource() throws Exception {
        bookmark(me, prague, true);
        mvc.perform(patch("/community/me/hashtags/visibility").with(as(wanderer.member()))
                .with(json(Map.of("hashtag", "체코", "isPublic", false))));
        saved(me).andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(containsInAnyOrder("프라하", "카를교")));

        mvc.perform(delete("/sessions/{id}", prague.getId()).with(as(wanderer.member()))).andExpect(okEnvelope());

        saved(me).andExpect(jsonPath("$.data.mindmaps").value(hasSize(0)));
    }

    @Test
    @DisplayName("북마크: 저장할 노드가 없는 카드는 400 이고, 아무것도 남기지 않는다")
    void bookmarkWithoutNodes() throws Exception {
        BrowsingSession empty = session(wanderer.member(), "링크 없는 세션", "2026-04-21T10:00:00",
                "2026-04-21T11:00:00", 0, false, true, List.of("프라하"), List.of(new Source("위험", "javascript:alert(1)", "발췌")), null);

        bookmark(me, empty, true).andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, "저장할 노드가 없는 마인드맵입니다."));
        assertThat(activityTags(me, "BOOKMARK")).isEmpty();

        bookmark(me, empty, false).andExpect(okEnvelope()).andExpect(jsonPath("$.data.bookmarkCount").value(0));
    }

    // ---------- 숫자 · 입력 ----------

    @Test
    @DisplayName("탈퇴한 사람의 행동은 숫자에서 빠지고 행은 남는다")
    void withdrawnActors() throws Exception {
        like(other, prague, true);
        exploring(other, prague, true);
        bookmark(other, prague, true);
        like(me, prague, true);
        exploring(me, prague, true);
        bookmark(me, prague, true);

        withdraw(other);

        CommunityCard card = card(prague, me);
        assertThat(card.likeCount()).isEqualTo(1);
        assertThat(card.exploringCount()).isEqualTo(1);
        assertThat(card.bookmarkCount()).isEqualTo(1);
        like(me, prague, true).andExpect(jsonPath("$.data.likeCount").value(1));
        exploring(me, prague, true).andExpect(jsonPath("$.data.exploringCount").value(1));
        bookmark(me, prague, true).andExpect(jsonPath("$.data.bookmarkCount").value(1));
        assertThat(rows("community_mindmap_likes", other)).isEqualTo(1);
        assertThat(rows("community_mindmap_explorations", other)).isEqualTo(1);
    }

    @Test
    @DisplayName("필수 값이 없으면 400, 로그인하지 않으면 401 이다")
    void validation() throws Exception {
        mvc.perform(patch(LIKE, prague.getId()).with(as(me)).with(json("{}")))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID));
        mvc.perform(patch(EXPLORING, prague.getId()).with(as(me)).with(json("{}")))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID));
        mvc.perform(patch(BOOKMARK, prague.getId()).with(as(me)).with(json("{}")))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, INVALID));
        mvc.perform(patch(LIKE, 0).with(as(me)).with(json(Map.of("liked", true)))).andExpect(status().isBadRequest());
        mvc.perform(patch(LIKE, prague.getId()).with(json(Map.of("liked", true)))).andExpect(status().isUnauthorized());
    }
}
