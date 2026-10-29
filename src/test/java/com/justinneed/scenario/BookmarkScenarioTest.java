package com.justinneed.scenario;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 북마크 화면 (/bookmarks): 마인드맵 · 세션 · 해시태그 묶음 탭.
 * FE: src/features/community/BookmarksView.tsx, 목 BOOKMARKED_MINDMAPS(savedNodeIds 강조) · BOOKMARK_TAG_BUNDLES.
 * 북마크 저장은 다른 사용자 프로필 화면에서 한다 (OtherProfileScenarioTest).
 */
@DisplayName("북마크 화면")
class BookmarkScenarioTest extends ScenarioTestSupport {

    private static final String BUNDLES = "/community/me/bookmarks/hashtag-bundles";
    private static final String MINDMAPS = "/community/me/bookmarks/mindmaps";
    private static final String PRAGUE_CASTLE = "https://www.hrad.cz/en/prague-castle-for-visitors";
    private static final String PRAGUE_EU = "https://www.prague.eu/en";
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z";

    private Member me;
    private Wanderer wanderer;

    @BeforeEach
    void seed() {
        me = member("sprout");
        wanderer = seedWanderer();
    }

    private Long saveBundle(String... hashtags) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (String hashtag : hashtags) {
            ids.add(hashtagId(wanderer.member(), hashtag));
        }
        return readLong(mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of(
                        "targetType", "HASHTAG", "targetIds", ids, "saveType", "HASHTAG_BUNDLE"))))
                .andExpect(status().isCreated()), "$.data.bookmarkId");
    }

    private Long saveMiniMindmap(List<Long> nodeIds) throws Exception {
        return readLong(mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of(
                        "targetType", "NODE", "targetIds", nodeIds, "saveType", "MINI_MINDMAP",
                        "sourceMindmapId", wanderer.id()))))
                .andExpect(status().isCreated()), "$.data.bookmarkId");
    }

    private List<Long> pragueNodes() throws Exception {
        return List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE), nodeId(me, wanderer.member(), PRAGUE_EU));
    }

    @Test
    @DisplayName("해시태그 묶음 탭(COM-2-1): 저장한 묶음이 최신순으로 해시태그 이름 · 저장 시각(UTC ISO)과 함께 온다")
    void hashtagBundlesNewestFirst() throws Exception {
        Long first = saveBundle("프라하", "야간열차");
        Long second = saveBundle("비엔나");

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(second, first))))
                .andExpect(jsonPath("$.data.bundles[0].hashtags").value(contains("비엔나")))
                .andExpect(jsonPath("$.data.bundles[1].hashtags").value(contains("프라하", "야간열차")))
                .andExpect(jsonPath("$.data.bundles[0].savedAt").value(matchesPattern(ISO_INSTANT)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("미니 마인드맵 탭(COM-2-2): 저장한 노드 · 해시태그 · 연결선 스냅샷과 원래 마인드맵 id(작성자 id)가 온다")
    void miniMindmapSnapshot() throws Exception {
        Long bookmarkId = saveMiniMindmap(pragueNodes());

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps", hasSize(1)))
                .andExpect(jsonPath("$.data.mindmaps[0].bookmarkId").value(bookmarkId))
                .andExpect(jsonPath("$.data.mindmaps[0].sourceMindmapId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(containsInAnyOrder("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(containsInAnyOrder(PRAGUE_CASTLE, PRAGUE_EU)))
                // 노드 2개 × 프라하 세션 태그 3개
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(6)))
                .andExpect(jsonPath("$.data.mindmaps[0].savedAt").value(matchesPattern(ISO_INSTANT)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("탭 목록은 size 만큼 끊어 오고 hasNext 로 다음 페이지가 있는지 알려준다")
    void paging() throws Exception {
        saveBundle("프라하");
        saveBundle("비엔나");
        saveBundle("야간열차");

        mvc.perform(get(BUNDLES).param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.bundles", hasSize(2)))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(BUNDLES).param("page", "1").param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.bundles[*].hashtags[0]").value(contains("프라하")))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("원래 마인드맵에서 보기(COM-2-2): 작성자의 지금 마인드맵과 내가 저장한 노드 id 가 강조 목록으로 온다")
    void originalMindmapHighlightsSavedNodes() throws Exception {
        List<Long> saved = pragueNodes();
        Long bookmarkId = saveMiniMindmap(saved);

        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.mindmap.ownerId").value(wanderer.id()))
                .andExpect(jsonPath("$.data.mindmap.nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.mindmap.nodes", hasSize(6)))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(containsInAnyOrder(ids(saved.toArray(Long[]::new)))));
    }

    @Test
    @DisplayName("작성자가 원본 세션을 비공개로 돌리면 원래 마인드맵의 강조는 빠지지만 내가 저장한 스냅샷은 그대로다")
    void snapshotSurvivesSourceGoingPrivate() throws Exception {
        Long bookmarkId = saveMiniMindmap(pragueNodes());

        mvc.perform(patch("/sessions/{id}", wanderer.prague().getId()).with(as(wanderer.member()))
                .with(json(Map.of("isPublic", false)))).andExpect(okEnvelope());

        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me)))
                .andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.highlightedNodeIds", hasSize(0)))
                .andExpect(jsonPath("$.data.mindmap.nodes[*].sourceUrl").value(not(hasItem(PRAGUE_CASTLE))));
        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(containsInAnyOrder(PRAGUE_CASTLE, PRAGUE_EU)));
    }

    @Test
    @DisplayName("해시태그 묶음 북마크는 원래 마인드맵 보기 대상이 아니고(400), 남의 북마크 id 는 404")
    void originalErrors() throws Exception {
        Long bundle = saveBundle("프라하");
        Long mini = saveMiniMindmap(pragueNodes());
        Member foodtrip = member("foodtrip");

        mvc.perform(get("/community/me/bookmarks/{id}/original", bundle).with(as(me)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/community/me/bookmarks/{id}/original", mini).with(as(foodtrip)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("북마크 해제(COM-4-3): 지우면 목록에서 빠지고, 남의 북마크를 지우거나 다시 지우면 404")
    void unbookmark() throws Exception {
        Long prague = saveBundle("프라하");
        Long vienna = saveBundle("비엔나");
        Member foodtrip = member("foodtrip");

        mvc.perform(delete("/community/bookmarks/{id}", vienna).with(as(foodtrip))).andExpect(status().isNotFound());
        mvc.perform(delete("/community/bookmarks/{id}", vienna).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data").value(nullValue()));
        mvc.perform(delete("/community/bookmarks/{id}", vienna).with(as(me))).andExpect(status().isNotFound());

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(prague))));
    }

    @Test
    @DisplayName("북마크한 적이 없으면 두 탭 모두 빈 목록이라 FE 가 '북마크한 항목이 없어요'를 보여준다")
    void emptyTabs() throws Exception {
        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bundles", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
    }

    @Test
    @DisplayName("로그인하지 않으면 북마크 목록 · 해제는 401")
    void requiresLogin() throws Exception {
        mvc.perform(get(BUNDLES)).andExpect(status().isUnauthorized());
        mvc.perform(get(MINDMAPS)).andExpect(status().isUnauthorized());
        mvc.perform(delete("/community/bookmarks/{id}", 1)).andExpect(status().isUnauthorized());
    }

    @Test
    @Disabled("FE 기대와 다름: 미니 마인드맵 카드는 제목(post.title)과 'by 작성자'를 보여주는데 스냅샷 title 은 고정 영문 "
            + "'Saved community mindmap' 이고 작성자 닉네임이 없음 — 필드명 ownerNickname 은 제안, 결정 후 켠다")
    @DisplayName("미니 마인드맵 카드에 원래 마인드맵 제목과 작성자 닉네임이 온다")
    void miniMindmapCardHasOwnerAndTitle() throws Exception {
        saveMiniMindmap(pragueNodes());

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[0].ownerNickname").value("wanderer"))
                .andExpect(jsonPath("$.data.mindmaps[0].title").value(not("Saved community mindmap")));
    }

    @Test
    @Disabled("API 미구현: 해시태그 묶음 이름 — FE 카드 제목은 묶음 이름(예: '동유럽 자료')인데 저장 · 목록 응답에 name 이 없음 "
            + "(저장 시 name 을 받는 방식은 제안), 구현되면 켠다")
    @DisplayName("해시태그 묶음을 이름을 붙여 저장하면 목록 카드에 그 이름이 온다")
    void bundleHasName() throws Exception {
        mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of(
                        "targetType", "HASHTAG", "targetIds", List.of(hashtagId(wanderer.member(), "프라하")),
                        "saveType", "HASHTAG_BUNDLE", "name", "동유럽 자료"))))
                .andExpect(status().isCreated());

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[0].name").value("동유럽 자료"));
    }

    @Test
    @Disabled("API 미구현: 저장한 세션 탭 (A표 '세션 북마크' — 보류였다가 반영 확인 중) — POST /community/bookmarks 의 targetType SESSION 과 "
            + "GET /community/me/bookmarks/sessions 는 제안, 구현되면 켠다")
    @DisplayName("세션 탭: 다른 사람 공유 세션을 저장하면 저장한 세션 목록에 카드 정보와 함께 온다")
    void savedSessions() throws Exception {
        mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of(
                        "targetType", "SESSION", "targetIds", List.of(wanderer.prague().getId()), "saveType", "SESSION"))))
                .andExpect(status().isCreated());

        mvc.perform(get("/community/me/bookmarks/sessions").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sessions[0].sessionId").value(wanderer.prague().getId()))
                .andExpect(jsonPath("$.data.sessions[0].nickname").value("wanderer"))
                .andExpect(jsonPath("$.data.sessions[0].hashtags").value(contains("프라하", "카를교", "체코")));
    }
}
