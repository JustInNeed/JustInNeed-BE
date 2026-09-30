package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import com.justinneed.community.domain.CommunityProfile;
import java.util.List;

class HashtagVisibilityTest extends CommunityTestSupport {
    @Test
    void canHideAnInterestBeforeItIsUsedByASession() throws Exception {
        var profile = new CommunityProfile(ownerId);
        profile.replaceInterests(List.of("Java"));
        profiles.saveAndFlush(profile);
        mvc.perform(patch("/community/me/hashtags/visibility").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"JAVA\",\"isPublic\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isPublic").value(false));
        assertThat(profiles.findById(ownerId).orElseThrow().getHiddenHashtags()).containsExactly("java");
    }

    @Test
    void hidingTagSuppressesMixedSessionsAndRestoringMakesThemVisible() throws Exception {
        session(ownerId, true, "Java", "Secret");
        session(ownerId, true, "Other");
        mvc.perform(patch("/community/me/hashtags/visibility").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"SECRET\",\"isPublic\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].hashtag").value("Other"));
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(ownerId)))
                .andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(patch("/community/me/hashtags/visibility").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"secret\",\"isPublic\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.length()").value(3));
        assertThat(profiles.findById(ownerId).orElseThrow().getHiddenHashtags()).isEmpty();
    }

    @Test
    void onlyChangesOwnedHashtagsAndRejectsMalformedRequests() throws Exception {
        session(ownerId, true, "Java");
        mvc.perform(patch("/community/me/hashtags/visibility").header("Authorization", auth(viewerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hashtag\":\"Java\",\"isPublic\":false}"))
                .andExpect(status().isBadRequest());
        for (String body : new String[]{"{\"hashtag\":\"Java\"}", "{\"hashtag\":\"#Java\",\"isPublic\":false}"}) {
            mvc.perform(patch("/community/me/hashtags/visibility").header("Authorization", auth(ownerId))
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/community/me/hashtags").header("Authorization", auth(ownerId)))
                .andExpect(jsonPath("$.data[0].isPublic").value(true));
        mvc.perform(get("/community/me/hashtags")).andExpect(status().isUnauthorized());
    }
}
