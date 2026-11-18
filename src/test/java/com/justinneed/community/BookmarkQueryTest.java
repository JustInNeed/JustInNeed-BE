package com.justinneed.community;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.community.bookmark.BookmarkSnapshot;
import com.justinneed.community.bookmark.CommunityBookmark;
import com.justinneed.community.bookmark.CommunityBookmarkRepository;
import com.justinneed.community.bookmark.SaveType;
import com.justinneed.community.bookmark.TargetType;
import com.justinneed.community.catalog.CommunityHashtag;
import com.justinneed.community.catalog.CommunityHashtagRepository;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.graph.CommunityNode;
import com.justinneed.community.graph.CommunityNodeRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookmarkQueryTest extends CommunityTestSupport {
    @Autowired CommunityBookmarkRepository bookmarks;
    @Autowired CommunityHashtagRepository tags;
    @Autowired CommunityNodeRepository nodes;

    @Test
    void listsOnlyOwnersSnapshotsWithStablePaginationAndRelations() throws Exception {
        var tag = tags.saveAndFlush(new CommunityHashtag(ownerId, "Java")).getId();
        var node =
                nodes.saveAndFlush(
                                new CommunityNode(
                                        viewerId, session(viewerId, true, "Java").getId(), "saved"))
                        .getId();
        var snapshot =
                new BookmarkSnapshot(
                        "Saved",
                        List.of(new BookmarkSnapshot.Tag(tag, ownerId, "Java")),
                        List.of(
                                new BookmarkSnapshot.Node(
                                        node,
                                        "Saved node",
                                        "https://example.com",
                                        null,
                                        NodeVisibility.URL_ONLY)),
                        List.of(new BookmarkSnapshot.Edge("tag:" + tag, "node:" + node)));
        for (int i = 0; i < 2; i++)
            bookmarks.save(
                    new CommunityBookmark(
                            ownerId,
                            SaveType.HASHTAG_BUNDLE,
                            TargetType.HASHTAG,
                            "key" + i,
                            null,
                            List.of(1L),
                            snapshot,
                            Instant.parse("2026-09-20T00:00:00Z")));
        bookmarks.saveAndFlush(
                new CommunityBookmark(
                        ownerId,
                        SaveType.MINI_MINDMAP,
                        TargetType.NODE,
                        "mini",
                        viewerId,
                        List.of(node),
                        snapshot,
                        Instant.now()));
        mvc.perform(
                        get("/community/me/bookmarks/hashtag-bundles")
                                .param("size", "1")
                                .header("Authorization", auth(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bundles.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(ownerId)))
                .andExpect(
                        jsonPath("$.data.mindmaps[0].nodes[0].label").value("https://example.com"))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[0].to").value("node:" + node))
                .andExpect(jsonPath("$.data.mindmaps[0].hashtags[0].name").value("Java"));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.mindmaps.length()").value(0));
        mvc.perform(get("/community/me/bookmarks/mindmaps")).andExpect(status().isUnauthorized());
        mvc.perform(
                        get("/community/me/bookmarks/mindmaps")
                                .param("size", "101")
                                .header("Authorization", auth(ownerId)))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        get("/community/me/bookmarks/mindmaps")
                                .param("page", "-1")
                                .header("Authorization", auth(ownerId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hidesMiniMindmapWhoseNodeRowNoLongerExists() throws Exception {
        var gone =
                new BookmarkSnapshot(
                        "Saved",
                        List.of(),
                        List.of(
                                new BookmarkSnapshot.Node(
                                        Long.MAX_VALUE,
                                        "Saved node",
                                        "https://example.com",
                                        null,
                                        NodeVisibility.URL_ONLY)),
                        List.of());
        bookmarks.saveAndFlush(
                new CommunityBookmark(
                        viewerId,
                        SaveType.MINI_MINDMAP,
                        TargetType.NODE,
                        "gone-node",
                        ownerId,
                        List.of(Long.MAX_VALUE),
                        gone,
                        Instant.now()));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.mindmaps.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("[COM-2-1@2 R1] 저장한 태그가 더는 없으면 해시태그 묶음이 목록에서 빠진다")
    void hidesSnapshotHashtagsWhoseHashtagNoLongerExists() throws Exception {
        var gone =
                new BookmarkSnapshot(
                        "Saved",
                        List.of(new BookmarkSnapshot.Tag(Long.MAX_VALUE, ownerId, "Gone")),
                        List.of(),
                        List.of());
        bookmarks.saveAndFlush(
                new CommunityBookmark(
                        viewerId,
                        SaveType.HASHTAG_BUNDLE,
                        TargetType.HASHTAG,
                        "gone",
                        null,
                        List.of(Long.MAX_VALUE),
                        gone,
                        Instant.now()));
        mvc.perform(
                        get("/community/me/bookmarks/hashtag-bundles")
                                .header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.bundles.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    @DisplayName("[COM-2-1@1 S3] 저장 시각이 같으면 id 가 큰 북마크가 먼저 온다")
    void sameSavedAtComesByNewestId() throws Exception {
        var tag = tags.saveAndFlush(new CommunityHashtag(ownerId, "Java")).getId();
        session(ownerId, true, "Java");
        var snapshot =
                new BookmarkSnapshot(
                        "Saved",
                        List.of(new BookmarkSnapshot.Tag(tag, ownerId, "Java")),
                        List.of(),
                        List.of());
        Instant same = Instant.parse("2026-09-20T00:00:00Z");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(
                    bookmarks
                            .saveAndFlush(
                                    new CommunityBookmark(
                                            viewerId,
                                            SaveType.HASHTAG_BUNDLE,
                                            TargetType.HASHTAG,
                                            "same" + i,
                                            null,
                                            List.of(tag),
                                            snapshot,
                                            same))
                            .getId());
        }
        mvc.perform(
                        get("/community/me/bookmarks/hashtag-bundles")
                                .header("Authorization", auth(viewerId)))
                .andExpect(
                        jsonPath("$.data.bundles[*].bookmarkId")
                                .value(
                                        contains(
                                                ids.get(2).intValue(),
                                                ids.get(1).intValue(),
                                                ids.get(0).intValue())));
    }
}
