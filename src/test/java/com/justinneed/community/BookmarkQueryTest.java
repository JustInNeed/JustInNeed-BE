package com.justinneed.community;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.domain.NodeVisibility;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BookmarkQueryTest extends CommunityTestSupport {
    @Autowired CommunityBookmarkRepository bookmarks;
    @Test
    void listsOnlyOwnersSnapshotsWithStablePaginationAndRelations() throws Exception {
        var snapshot = new BookmarkSnapshot("Saved", List.of(new BookmarkSnapshot.Tag(1L, ownerId, "Java")),
                List.of(new BookmarkSnapshot.Node(7L, "Saved node", "https://example.com", null, NodeVisibility.URL_ONLY)),
                List.of(new BookmarkSnapshot.Edge("tag:1", "node:7")));
        for (int i = 0; i < 2; i++) bookmarks.save(new CommunityBookmark(ownerId, SaveType.HASHTAG_BUNDLE,
                TargetType.HASHTAG, "key" + i, null, List.of(1L), snapshot, Instant.parse("2026-09-20T00:00:00Z")));
        bookmarks.saveAndFlush(new CommunityBookmark(ownerId, SaveType.MINI_MINDMAP, TargetType.NODE,
                "mini", viewerId, List.of(7L), snapshot, Instant.now()));
        mvc.perform(get("/community/me/bookmarks/hashtag-bundles").param("size", "1").header("Authorization", auth(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.bundles.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(ownerId)))
                .andExpect(jsonPath("$.data.mindmaps[0].nodes[0].label").value("Saved node"))
                .andExpect(jsonPath("$.data.mindmaps[0].edges[0].to").value("node:7"));
        mvc.perform(get("/community/me/bookmarks/mindmaps").header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.mindmaps.length()").value(0));
        mvc.perform(get("/community/me/bookmarks/mindmaps")).andExpect(status().isUnauthorized());
        mvc.perform(get("/community/me/bookmarks/mindmaps").param("size", "101").header("Authorization", auth(ownerId)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/community/me/bookmarks/mindmaps").param("page", "-1").header("Authorization", auth(ownerId)))
                .andExpect(status().isBadRequest());
    }
}
