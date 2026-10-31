package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.community.domain.*;
import com.justinneed.session.management.domain.Source;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PublicGraphTest extends CommunityTestSupport {
    @Autowired PublicGraphService graph;
    @Test
    void countsOnlyPublicLeavesRanksFiveTagsAndKeepsStableIds() throws Exception {
        var s = session(ownerId, true, "Java", "Spring", "AI", "Web", "Data", "Six");
        session(ownerId, false, "Secret");
        var first = graph.graph(ownerId, null);
        assertThat(first.nodes()).hasSize(1);
        assertThat(first.topHashtags()).hasSize(5);
        assertThat(first.nodes().get(0).content()).isNull();
        assertThat(first.nodes().get(0).label()).isEqualTo("https://example.com");
        var id = first.nodes().get(0).nodeId();
        s.replaceSources(List.of(new Source("new", "https://new.example.com", "text"),
                new Source("old", "https://example.com", "secret"), new Source("unsafe", "javascript:alert(1)", "bad")));
        cardSync.sync(s);
        assertThat(graph.graph(ownerId, null).nodes()).extracting(n -> n.nodeId()).contains(id);
        mvc.perform(get("/community/profiles/{id}", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.totalNodeCount").value(2))
                .andExpect(jsonPath("$.data.topHashtags.length()").value(5));
    }

    @Test
    void hiddenMixedTagStaysOutOfGraphAndCannotBeUsedAsFilter() throws Exception {
        session(ownerId, true, "Java", "Secret");
        var initial = graph.graph(ownerId, null);
        var secret = initial.hashtags().stream().filter(t -> t.name().equals("Secret")).findFirst().orElseThrow();
        var profile = new CommunityProfile(ownerId);
        profile.replaceHiddenHashtags(List.of("secret"));
        profiles.saveAndFlush(profile);
        var hidden = graph.graph(ownerId, null);
        assertThat(hidden.nodes()).hasSize(1);
        assertThat(hidden.hashtags()).extracting(t -> t.name()).containsExactly("Java");
        mvc.perform(get("/community/profiles/{id}/mindmap", ownerId)
                .param("hashtagId", secret.hashtagId().toString())
                .header("Authorization", auth(viewerId))).andExpect(status().isNotFound());
    }
}
