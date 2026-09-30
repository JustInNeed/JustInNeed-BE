package com.justinneed.community;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.activity.CommunityActivityRepository;
import com.justinneed.community.bookmark.*;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.community.reaction.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

class InteractionTest extends CommunityTestSupport {
    @Autowired PublicGraphService graphs;
    @Autowired InteractionService interactions;
    @Autowired BookmarkCommandService commands;
    @Autowired CommunityBookmarkRepository bookmarks;
    @Autowired CommunityActivityRepository activity;

    @Test
    void likeIsIdempotentAndCanBeRemoved() throws Exception {
        for (int i = 0; i < 2; i++) mvc.perform(patch("/community/profiles/{id}/like", ownerId)
                .header("Authorization", auth(viewerId)).contentType(MediaType.APPLICATION_JSON).content("{\"liked\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.likeCount").value(1));
        mvc.perform(get("/community/profiles/{id}", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.likedByMe").value(true)).andExpect(jsonPath("$.data.likeCount").value(1));
        assertThat(interactions.like(viewerId, ownerId, false).likeCount()).isZero();
        assertThat(interactions.like(viewerId, ownerId, false).likeCount()).isZero();
        mvc.perform(patch("/community/profiles/{id}/like", ownerId).header("Authorization", auth(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"liked\":true}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/community/profiles/{id}/like", ownerId).header("Authorization", auth(viewerId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isBadRequest());
    }

    @Test
    void exploringRepeatedRequestsDoNotInflateCountsOrActivityAndHiddenTargetCanBeCancelled() {
        var s = session(ownerId, true, "Java");
        var node = graphs.graph(ownerId, null).nodes().get(0).nodeId();
        assertThat(interactions.exploring(viewerId, TargetType.NODE, node, true).exploringCount()).isEqualTo(1);
        assertThat(interactions.exploring(viewerId, TargetType.NODE, node, true).exploringCount()).isEqualTo(1);
        interactions.exploring(viewerId, TargetType.NODE, node, false);
        interactions.exploring(viewerId, TargetType.NODE, node, true);
        assertThat(activity.count()).isEqualTo(1);
        s.update(null, null, false, null, null, null);
        assertThat(interactions.exploring(viewerId, TargetType.NODE, node, false).exploringCount()).isZero();
    }

    @Test
    void snapshotPreservesRelationsIsPrivateAndDoesNotFollowSourceChanges() throws Exception {
        var s = session(ownerId, true, "Java", "Spring");
        var graph = graphs.graph(ownerId, null);
        var node = graph.nodes().get(0).nodeId();
        var saved = commands.save(viewerId, TargetType.NODE, List.of(node), SaveType.MINI_MINDMAP, ownerId);
        assertThat(commands.save(viewerId, TargetType.NODE, List.of(node), SaveType.MINI_MINDMAP, ownerId).bookmarkId())
                .isEqualTo(saved.bookmarkId());
        var snapshot = bookmarks.findById(saved.bookmarkId()).orElseThrow().getSnapshot();
        assertThat(snapshot.edges()).hasSize(2);
        assertThat(snapshot.nodes().get(0).content()).isNull();
        mvc.perform(delete("/community/bookmarks/{id}", saved.bookmarkId()).header("Authorization", auth(ownerId)))
                .andExpect(status().isNotFound());
        s.update(null, null, false, null, null, null);
        assertThat(graphs.original(viewerId, saved.bookmarkId()).highlightedNodeIds()).isEmpty();
        assertThat(bookmarks.findById(saved.bookmarkId()).orElseThrow().getSnapshot()).isEqualTo(snapshot);
        mvc.perform(delete("/community/bookmarks/{id}", saved.bookmarkId()).header("Authorization", auth(viewerId)))
                .andExpect(status().isOk());
        assertThat(bookmarks.findById(saved.bookmarkId())).isEmpty();
    }

    @Test
    void bookmarkApiValidatesCompleteSelectionAndSourceBeforeWriting() throws Exception {
        session(ownerId, true, "Java");
        var tag = graphs.graph(ownerId, null).hashtags().get(0).hashtagId();
        mvc.perform(post("/community/bookmarks").header("Authorization", auth(viewerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"HASHTAG\",\"targetIds\":[" + tag + ",999999],\"saveType\":\"HASHTAG_BUNDLE\"}"))
                .andExpect(status().isNotFound());
        assertThat(bookmarks.count()).isZero();
        assertThat(activity.count()).isZero();
        mvc.perform(post("/community/bookmarks").header("Authorization", auth(viewerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"HASHTAG\",\"targetIds\":[" + tag + "],\"saveType\":\"MINI_MINDMAP\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/community/bookmarks").header("Authorization", auth(viewerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"HASHTAG\",\"targetIds\":[" + tag + "],\"saveType\":\"HASHTAG_BUNDLE\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.saveType").value("HASHTAG_BUNDLE"));
        assertThat(bookmarks.count()).isEqualTo(1);
        mvc.perform(post("/community/bookmarks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void privateTargetsCannotBeBookmarked() throws Exception {
        var s = session(ownerId, true, "Java");
        var id = graphs.graph(ownerId, null).nodes().get(0).nodeId();
        s.delete();
        mvc.perform(post("/community/bookmarks").header("Authorization", auth(viewerId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"targetType\":\"NODE\",\"targetIds\":[" + id + "],\"saveType\":\"MINI_MINDMAP\",\"sourceMindmapId\":" + ownerId + "}"))
                .andExpect(status().isNotFound());
        assertThat(bookmarks.count()).isZero();
    }
}
