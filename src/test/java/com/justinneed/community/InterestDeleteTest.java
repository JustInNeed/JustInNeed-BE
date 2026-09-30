package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.domain.CommunityProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterestDeleteTest extends CommunityTestSupport {
    @Test
    void removesOnlyRequestedInterestIgnoringCaseAndPreservesSessionTags() throws Exception {
        var session = session(ownerId, true, "Java", "Other");
        var profile = new CommunityProfile(ownerId);
        profile.replaceInterests(List.of("First", "Java", "한글", "Last"));
        profile.replaceHiddenHashtags(List.of("java"));
        profiles.saveAndFlush(profile);
        mvc.perform(delete("/community/me/interests/{tag}", "JAVA").header("Authorization", auth(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0]").value("First"))
                .andExpect(jsonPath("$.data[2]").value("Last"));
        mvc.perform(delete("/community/me/interests/{tag}", "한글").header("Authorization", auth(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        assertThat(session.getTags()).containsExactly("Java", "Other");
        assertThat(profiles.findById(ownerId).orElseThrow().getHiddenHashtags()).containsExactly("java");
    }

    @Test
    void missingAndForeignInterestCannotBeDeleted() throws Exception {
        var profile = new CommunityProfile(ownerId);
        profile.replaceInterests(List.of("Java"));
        profiles.saveAndFlush(profile);
        mvc.perform(delete("/community/me/interests/Java").header("Authorization", auth(viewerId)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/community/me/interests/Missing").header("Authorization", auth(ownerId)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/community/me/interests/Java")).andExpect(status().isUnauthorized());
        assertThat(profiles.findById(ownerId).orElseThrow().getInterests()).containsExactly("Java");
    }
}
