package com.justinneed.community;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.search.CommunitySearchService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CommunitySearchTest extends CommunityTestSupport {
    @Autowired CommunitySearchService search;

    @Test
    void findsProfilesAndMindmapsCaseInsensitivelyWithDeterministicPagination() {
        session(ownerId, true, "Java");
        session(ownerId, true, "Java");
        session(viewerId, true, "java");
        var first = search.profiles("JAVA", 0, 1);
        assertThat(first.profiles()).hasSize(1);
        assertThat(first.profiles().get(0).userId()).isEqualTo(ownerId);
        assertThat(first.hasNext()).isTrue();
        var last = search.profiles("java", 1, 1);
        assertThat(last.profiles().get(0).userId()).isEqualTo(viewerId);
        assertThat(last.hasNext()).isFalse();
        assertThat(search.mindmaps("java", 0, 10).mindmaps()).hasSize(2);
        assertThat(search.profiles("unknown", 0, 10).profiles()).isEmpty();
        assertThat(search.profiles("java", Integer.MAX_VALUE, 100).profiles()).isEmpty();
    }

    @Test
    void hiddenPrivateDeletedAndInterestOnlyGraphsAreFiltered() {
        session(ownerId, true, "Java", "Hidden");
        session(ownerId, false, "Private");
        session(ownerId, true, "Deleted").delete();
        var profile = new CommunityProfile(ownerId);
        profile.replaceHiddenHashtags(List.of("hidden"));
        profile.replaceInterests(List.of("Interest"));
        profiles.saveAndFlush(profile);
        assertThat(search.profiles("Java", 0, 10).profiles()).isEmpty();
        assertThat(search.mindmaps("Hidden", 0, 10).mindmaps()).isEmpty();
        assertThat(search.profiles("Private", 0, 10).profiles()).isEmpty();
        assertThat(search.profiles("Deleted", 0, 10).profiles()).isEmpty();
        assertThat(search.profiles("Interest", 0, 10).profiles()).hasSize(1);
        assertThat(search.mindmaps("Interest", 0, 10).mindmaps()).isEmpty();
    }

    @Test
    void searchRequiresAuthenticationAndValidParameters() throws Exception {
        mvc.perform(get("/community/search/profiles").param("hashtag", "Java")).andExpect(status().isUnauthorized());
        mvc.perform(get("/community/search/profiles").param("hashtag", "Java").param("page", "-1")
                .header("Authorization", auth(viewerId))).andExpect(status().isBadRequest());
        mvc.perform(get("/community/search/mindmaps").param("hashtag", "Java").param("size", "101")
                .header("Authorization", auth(viewerId))).andExpect(status().isBadRequest());
        mvc.perform(get("/community/search/profiles").param("hashtag", "bad!")
                .header("Authorization", auth(viewerId))).andExpect(status().isBadRequest());
        mvc.perform(get("/community/search/mindmaps").param("hashtag", " ")
                .header("Authorization", auth(viewerId))).andExpect(status().isBadRequest());
    }
}
