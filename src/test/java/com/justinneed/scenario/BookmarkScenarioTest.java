package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.justinneed.community.bookmark.BookmarkSnapshot;
import com.justinneed.community.bookmark.CommunityBookmark;
import com.justinneed.community.bookmark.CommunityBookmarkRepository;
import com.justinneed.community.bookmark.SaveType;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

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
    private static final String NIGHTJET = "https://www.nightjet.com/en";
    private static final String WIEN = "https://www.wien.info/en";
    private static final String BELVEDERE = "https://www.belvedere.at/en";
    private static final String CAFE = "https://www.cafecentral.wien/en/";
    private static final String RECENT_BOOKMARKS = "/community/recommendations/mindmaps/recent-bookmarks";
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z";

    @Autowired
    private CommunityBookmarkRepository bookmarkRows;

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

    private Long saveNodeBundle(List<Long> nodeIds) throws Exception {
        return readLong(mvc.perform(post("/community/bookmarks").with(as(me)).with(json(Map.of(
                        "targetType", "NODE", "targetIds", nodeIds, "saveType", "HASHTAG_BUNDLE"))))
                .andExpect(status().isCreated()), "$.data.bookmarkId");
    }

    private void setSessionPublic(BrowsingSession session, boolean isPublic) throws Exception {
        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(wanderer.member()))
                .with(json(Map.of("isPublic", isPublic)))).andExpect(okEnvelope());
    }

    private void expectUnavailableOriginal(Long bookmarkId) throws Exception {
        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.sourceAvailable").value(false))
                .andExpect(jsonPath("$.data.mindmap").value(nullValue()))
                .andExpect(jsonPath("$.data.highlightedNodeIds", hasSize(0)));
    }

    private void setWandererHashtagPublic(String hashtag, boolean isPublic) throws Exception {
        mvc.perform(patch("/community/me/hashtags/{id}/visibility", hashtagId(wanderer.member(), hashtag))
                        .with(as(wanderer.member())).with(json(Map.of("isPublic", isPublic))))
                .andExpect(okEnvelope());
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
    @DisplayName("작성자가 원본 세션을 비공개로 돌리면 저장한 사람에게도 북마크가 안 보이고 원래 마인드맵 보기도 막힌다 (행 · 스냅샷은 그대로)")
    void sourceGoingPrivateHidesBookmark() throws Exception {
        Long bookmarkId = saveMiniMindmap(pragueNodes());
        BookmarkSnapshot saved = bookmarkRows.findById(bookmarkId).orElseThrow().getSnapshot();

        setSessionPublic(wanderer.prague(), false);

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        expectUnavailableOriginal(bookmarkId);
        assertThat(bookmarkRows.findById(bookmarkId).orElseThrow().getSnapshot()).isEqualTo(saved);
    }

    @Test
    @DisplayName("원본 세션을 다시 공개하면 북마크가 저장했던 그대로 돌아오고 원래 마인드맵 강조도 돌아온다")
    void sourceMadePublicAgainShowsBookmark() throws Exception {
        List<Long> saved = pragueNodes();
        Long bookmarkId = saveMiniMindmap(saved);
        setSessionPublic(wanderer.prague(), false);

        setSessionPublic(wanderer.prague(), true);

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(bookmarkId))))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(containsInAnyOrder(PRAGUE_CASTLE, PRAGUE_EU)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(6)));
        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me)))
                .andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(containsInAnyOrder(ids(saved.toArray(Long[]::new)))));
    }

    @Test
    @DisplayName("원본 세션을 지우면 북마크가 안 보이고, 숨겨진 북마크도 저장한 사람이 해제할 수 있다")
    void sourceDeletedHidesBookmark() throws Exception {
        Long bookmarkId = saveMiniMindmap(pragueNodes());

        mvc.perform(delete("/sessions/{id}", wanderer.prague().getId()).with(as(wanderer.member()))).andExpect(okEnvelope());

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        expectUnavailableOriginal(bookmarkId);
        assertThat(bookmarkRows.findById(bookmarkId)).isPresent();

        mvc.perform(delete("/community/bookmarks/{id}", bookmarkId).with(as(me))).andExpect(okEnvelope());
        assertThat(bookmarkRows.findById(bookmarkId)).isEmpty();
    }

    @Test
    @DisplayName("세션 두 개에서 저장한 미니 마인드맵은 비공개가 된 세션의 노드 · 연결선 · 그 세션에만 있던 태그만 빠진다")
    void partlyHiddenMiniMindmap() throws Exception {
        Long castle = nodeId(me, wanderer.member(), PRAGUE_CASTLE);
        Long wien = nodeId(me, wanderer.member(), WIEN);
        Long bookmarkId = saveMiniMindmap(List.of(castle, wien));

        setSessionPublic(wanderer.vienna(), false);

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(bookmarkId))))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(contains(PRAGUE_CASTLE)))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(containsInAnyOrder("프라하", "카를교", "체코")))
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(3)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[*].to").value(not(hasItem("node:" + wien))));
        mvc.perform(get("/community/me/bookmarks/{id}/original", bookmarkId).with(as(me)))
                .andExpect(jsonPath("$.data.sourceAvailable").value(true))
                .andExpect(jsonPath("$.data.highlightedNodeIds").value(contains(ids(castle))));
    }

    @Test
    @DisplayName("해시태그가 없는 세션의 노드를 저장한 미니 마인드맵도 원본이 비공개가 되면 빈 카드로 남지 않고 숨겨진다")
    void taglessMiniMindmapIsHidden() throws Exception {
        String link = "https://example.com/bare";
        BrowsingSession bare = session(wanderer.member(), "태그 없는 세션", "2026-04-15T10:00:00", "2026-04-15T11:00:00", 1,
                false, true, List.of(), List.of(new Source("글", link, "발췌")), "본문");
        Long bookmarkId = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), link)));
        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(bookmarkId))));

        setSessionPublic(bare, false);

        mvc.perform(get(MINDMAPS).with(as(me))).andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        expectUnavailableOriginal(bookmarkId);
    }

    @Test
    @DisplayName("태그가 섞인 세션은 태그 하나만 비공개가 돼도 세션 상세처럼 원본이 안 보이는 것으로 보고 북마크를 숨긴다")
    void mixedTagSessionFollowsVisibilityRule() throws Exception {
        setWandererHashtagPublic("카페", true);
        Long bookmarkId = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), CAFE)));

        setWandererHashtagPublic("카페", false);

        mvc.perform(get(MINDMAPS).with(as(me))).andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        expectUnavailableOriginal(bookmarkId);
    }

    @Test
    @DisplayName("작성자가 탈퇴하면 원본 세션이 안 보이는 것이라 노드 북마크도 숨긴다")
    void withdrawnOwnerHidesBookmark() throws Exception {
        Long mini = saveMiniMindmap(pragueNodes());
        Long bundle = saveNodeBundle(pragueNodes());

        wanderer.member().withdraw(null, LocalDateTime.of(2026, 10, 30, 9, 0));
        members.saveAndFlush(wanderer.member());

        mvc.perform(get(MINDMAPS).with(as(me))).andExpect(jsonPath("$.data.mindmaps", hasSize(0)));
        mvc.perform(get(BUNDLES).with(as(me))).andExpect(jsonPath("$.data.bundles", hasSize(0)));
        expectUnavailableOriginal(mini);
        assertThat(bookmarkRows.findById(bundle)).isPresent();
    }

    @Test
    @DisplayName("숨겨진 북마크는 페이지를 차지하지 않는다: 페이지가 짧거나 비지 않고 hasNext 도 보이는 북마크 기준이다")
    void hiddenBookmarksDoNotShortenPages() throws Exception {
        Long wien = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), WIEN)));
        saveMiniMindmap(List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE)));
        Long belvedere = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), BELVEDERE)));
        saveMiniMindmap(List.of(nodeId(me, wanderer.member(), PRAGUE_EU)));
        Long nightjet = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), NIGHTJET)));

        setSessionPublic(wanderer.prague(), false);

        mvc.perform(get(MINDMAPS).param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(nightjet, belvedere))))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get(MINDMAPS).param("page", "1").param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(wien))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mvc.perform(get(MINDMAPS).param("page", "2").param("size", "2").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        // 마지막 보이는 북마크 뒤에 숨겨진 북마크만 남아도 다음 페이지가 있다고 하지 않는다
        mvc.perform(get(MINDMAPS).param("size", "3").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps", hasSize(3)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("북마크 스캔 묶음(100개)을 넘어가도 보이는 북마크로 페이지를 채운다")
    void pagingCrossesScanBatches() throws Exception {
        Long stored = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE)));
        Long visible = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), WIEN)));
        BookmarkSnapshot hidden = bookmarkRows.findById(stored).orElseThrow().getSnapshot();
        List<Long> target = bookmarkRows.findById(stored).orElseThrow().getTargetIds();
        Instant newer = bookmarkRows.findById(visible).orElseThrow().getSavedAt().plusSeconds(1);
        for (int i = 0; i < 120; i++) {
            bookmarkRows.save(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.NODE, "scan-" + i,
                    wanderer.id(), target, hidden, newer.plusSeconds(i)));
        }
        bookmarkRows.flush();

        setSessionPublic(wanderer.prague(), false);

        mvc.perform(get(MINDMAPS).param("size", "1").with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(visible))))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("노드로 저장한 해시태그 묶음은 노드의 원본 세션을 따른다: 비공개 · 삭제면 안 보이고 다시 공개하면 돌아온다")
    void nodeBundleFollowsSourceSession() throws Exception {
        Long bundle = saveNodeBundle(pragueNodes());

        setSessionPublic(wanderer.prague(), false);
        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles", hasSize(0)))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        setSessionPublic(wanderer.prague(), true);
        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(bundle))));

        mvc.perform(delete("/sessions/{id}", wanderer.prague().getId()).with(as(wanderer.member()))).andExpect(okEnvelope());
        mvc.perform(get(BUNDLES).with(as(me))).andExpect(jsonPath("$.data.bundles", hasSize(0)));
    }

    @Test
    @DisplayName("해시태그로 저장한 묶음은 세션과 이어져 있지 않아 세션 공개 여부와 상관없이 해시태그 공개 규칙만 따른다")
    void hashtagBundleHasNoSourceSession() throws Exception {
        Long bundle = saveBundle("프라하");

        setSessionPublic(wanderer.prague(), false);
        setSessionPublic(wanderer.nightjet(), false);

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(bundle))));

        setWandererHashtagPublic("프라하", false);

        mvc.perform(get(BUNDLES).with(as(me))).andExpect(jsonPath("$.data.bundles", hasSize(0)));
    }

    @Test
    @DisplayName("최근 저장 기반 추천: 숨겨진 북마크의 해시태그는 기준에서 빠지고, 원본을 다시 공개하면 돌아온다")
    void recentBasisFollowsSourceSession() throws Exception {
        saveMiniMindmap(pragueNodes());
        mvc.perform(get(RECENT_BOOKMARKS).with(as(me)))
                .andExpect(jsonPath("$.data.basisHashtags").value(containsInAnyOrder("프라하", "카를교", "체코")));

        setSessionPublic(wanderer.prague(), false);
        mvc.perform(get(RECENT_BOOKMARKS).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.basisHashtags", hasSize(0)));

        setSessionPublic(wanderer.prague(), true);
        mvc.perform(get(RECENT_BOOKMARKS).with(as(me)))
                .andExpect(jsonPath("$.data.basisHashtags").value(containsInAnyOrder("프라하", "카를교", "체코")));
    }

    @Test
    @DisplayName("최근 저장 기반 추천: 기준은 보이는 북마크 최근 20개까지다")
    void recentBasisUsesTwentyBookmarks() throws Exception {
        saveMiniMindmap(List.of(nodeId(me, wanderer.member(), WIEN)));
        Long stored = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE)));
        BookmarkSnapshot prague = bookmarkRows.findById(stored).orElseThrow().getSnapshot();
        List<Long> target = bookmarkRows.findById(stored).orElseThrow().getTargetIds();
        Instant newer = Instant.now().plusSeconds(60);
        // 비엔나 북마크가 20번째가 되도록 프라하 북마크를 19개로 맞춘다
        for (int i = 0; i < 18; i++) {
            bookmarkRows.save(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.NODE, "basis-" + i,
                    wanderer.id(), target, prague, newer.plusSeconds(i)));
        }
        bookmarkRows.flush();
        mvc.perform(get(RECENT_BOOKMARKS).with(as(me)))
                .andExpect(jsonPath("$.data.basisHashtags").value(hasItem("비엔나")));

        bookmarkRows.saveAndFlush(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.NODE, "basis-last",
                wanderer.id(), target, prague, newer.plusSeconds(100)));

        mvc.perform(get(RECENT_BOOKMARKS).with(as(me)))
                .andExpect(jsonPath("$.data.basisHashtags").value(not(hasItem("비엔나"))));
    }

    @Test
    @DisplayName("최근 저장 기반 추천: 숨겨진 북마크가 최근 20개 자리를 차지하지 않는다")
    void hiddenBookmarksDoNotTakeRecentSlots() throws Exception {
        Long stored = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE)));
        saveMiniMindmap(List.of(nodeId(me, wanderer.member(), WIEN)));
        BookmarkSnapshot hidden = bookmarkRows.findById(stored).orElseThrow().getSnapshot();
        List<Long> target = bookmarkRows.findById(stored).orElseThrow().getTargetIds();
        Instant newer = Instant.now().plusSeconds(60);
        for (int i = 0; i < 20; i++) {
            bookmarkRows.save(new CommunityBookmark(me.getId(), SaveType.MINI_MINDMAP, TargetType.NODE, "recent-" + i,
                    wanderer.id(), target, hidden, newer.plusSeconds(i)));
        }
        bookmarkRows.flush();

        setSessionPublic(wanderer.prague(), false);

        mvc.perform(get(RECENT_BOOKMARKS).with(as(me)))
                .andExpect(jsonPath("$.data.basisHashtags").value(contains("비엔나")));
    }

    @Test
    @DisplayName("해시태그 묶음 탭: 저장한 뒤 작성자가 비공개로 돌린 해시태그는 묶음에서 빠지고, 태그가 하나도 안 남은 묶음은 목록에서 빠진다")
    void bundleHidesHashtagsMadePrivateLater() throws Exception {
        Long both = saveBundle("프라하", "야간열차");
        Long vienna = saveBundle("비엔나");

        setWandererHashtagPublic("야간열차", false);
        setWandererHashtagPublic("비엔나", false);

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(both))))
                .andExpect(jsonPath("$.data.bundles[0].hashtags").value(contains("프라하")))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        // 스냅샷은 그대로라 다시 공개로 돌리면 다시 보인다
        setWandererHashtagPublic("야간열차", true);
        setWandererHashtagPublic("비엔나", true);

        mvc.perform(get(BUNDLES).with(as(me)))
                .andExpect(jsonPath("$.data.bundles[*].bookmarkId").value(contains(ids(vienna, both))))
                .andExpect(jsonPath("$.data.bundles[1].hashtags").value(contains("프라하", "야간열차")));
    }

    @Test
    @DisplayName("미니 마인드맵 탭: 저장한 뒤 작성자가 비공개로 돌린 해시태그는 그 연결선 · 그 태그에만 달린 노드와 함께 빠지고, 태그가 하나도 안 남은 카드는 빠진다")
    void miniMindmapHidesHashtagsMadePrivateLater() throws Exception {
        Long viennaTag = hashtagId(wanderer.member(), "비엔나");
        Long mixed = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), PRAGUE_CASTLE),
                nodeId(me, wanderer.member(), NIGHTJET), nodeId(me, wanderer.member(), WIEN)));
        Long viennaOnly = saveMiniMindmap(List.of(nodeId(me, wanderer.member(), BELVEDERE)));

        setWandererHashtagPublic("비엔나", false);

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(mixed))))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name")
                        .value(containsInAnyOrder("프라하", "카를교", "체코", "야간열차")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(containsInAnyOrder(PRAGUE_CASTLE, NIGHTJET)))
                // 프라하 성 × 프라하 세션 태그 3개 + 나이트젯 × 야간열차 · 프라하
                .andExpect(jsonPath("$.data.mindmaps[0].edges", hasSize(5)))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[*].from").value(not(hasItem("tag:" + viennaTag))))
                .andExpect(jsonPath("$.data.hasNext").value(false));

        setWandererHashtagPublic("비엔나", true);

        mvc.perform(get(MINDMAPS).with(as(me)))
                .andExpect(jsonPath("$.data.mindmaps[*].bookmarkId").value(contains(ids(viennaOnly, mixed))))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[*].name").value(contains("비엔나")))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[*].sourceUrl").value(contains(BELVEDERE)))
                .andExpect(jsonPath("$.data.mindmaps[1].nodes[*].sourceUrl")
                        .value(containsInAnyOrder(PRAGUE_CASTLE, NIGHTJET, WIEN)))
                .andExpect(jsonPath("$.data.mindmaps[1].edges", hasSize(6)));
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
