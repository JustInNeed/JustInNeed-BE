package com.justinneed.community;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.catalog.*;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.graph.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookmarkQueryTest extends CommunityTestSupport {
    @Autowired CommunityBookmarkRepository bookmarks;
    @Autowired CommunityHashtagRepository tags;
    @Autowired CommunityNodeRepository nodes;
    @Test
    void listsOnlyOwnersSnapshotsWithStablePaginationAndRelations() throws Exception {
        var tag = tags.saveAndFlush(new CommunityHashtag(ownerId, "Java")).getId();
        var node = nodes.saveAndFlush(new CommunityNode(viewerId, session(viewerId, true, "Java").getId(), "saved")).getId();
        var snapshot = new BookmarkSnapshot("Saved", List.of(new BookmarkSnapshot.Tag(tag, ownerId, "Java")),
                List.of(new BookmarkSnapshot.Node(node, "Saved node", "https://example.com", null, NodeVisibility.URL_ONLY)),
                List.of(new BookmarkSnapshot.Edge("tag:" + tag, "node:" + node)));
        for (int i = 0; i < 2; i++) bookmarks.save(new CommunityBookmark(ownerId, SaveType.HASHTAG_BUNDLE,
                TargetType.HASHTAG, "key" + i, null, List.of(1L), snapshot, Instant.parse("2026-09-20T00:00:00Z")));
        bookmarks.saveAndFlush(new CommunityBookmark(ownerId, SaveType.MINI_MINDMAP, TargetType.NODE,
                "mini", viewerId, List.of(node), snapshot, Instant.now()));
        mvc.perform(get("/community/me/bookmarks/hashtag-bundles").param("size", "1").header("Authorization", auth(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.bundles.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(ownerId)))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].label").value("Saved node"))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[0].to").value("node:" + node));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.mindmaps.length()").value(0));
        mvc.perform(get("/community/me/bookmarks/mindmaps")).andExpect(status().isUnauthorized());
        mvc.perform(get("/community/me/bookmarks/mindmaps").param("size", "101").header("Authorization", auth(ownerId)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/community/me/bookmarks/mindmaps").param("page", "-1").header("Authorization", auth(ownerId)))
                .andExpect(status().isBadRequest());
    }
    @Test
    void hidesMiniMindmapWhoseNodeRowNoLongerExists() throws Exception {
        var gone = new BookmarkSnapshot("Saved", List.of(),
                List.of(new BookmarkSnapshot.Node(Long.MAX_VALUE, "Saved node", "https://example.com", null, NodeVisibility.URL_ONLY)),
                List.of());
        bookmarks.saveAndFlush(new CommunityBookmark(viewerId, SaveType.MINI_MINDMAP, TargetType.NODE, "gone-node",
                ownerId, List.of(Long.MAX_VALUE), gone, Instant.now()));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.mindmaps.length()").value(0)).andExpect(jsonPath("$.data.hasNext").value(false));
    }
    @Test
    void hidesSnapshotHashtagsWhoseHashtagNoLongerExists() throws Exception {
        var gone = new BookmarkSnapshot("Saved", List.of(new BookmarkSnapshot.Tag(Long.MAX_VALUE, ownerId, "Gone")),
                List.of(), List.of());
        bookmarks.saveAndFlush(new CommunityBookmark(viewerId, SaveType.HASHTAG_BUNDLE, TargetType.HASHTAG, "gone",
                null, List.of(Long.MAX_VALUE), gone, Instant.now()));
        mvc.perform(get("/community/me/bookmarks/hashtag-bundles").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.bundles.length()").value(0)).andExpect(jsonPath("$.data.hasNext").value(false));
    }
}
