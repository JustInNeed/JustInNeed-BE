package com.justinneed.community;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.edit.ProfileEditService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ProfileEditContractTest extends CommunityTestSupport {
    @Autowired ProfileEditService edit;
    @Test
    void supportsDesignedPathsAndOwnerScopedIds() throws Exception {
        var session = session(ownerId, false, "Java");
        var tag = edit.hashtags(ownerId).get(0);
        mvc.perform(patch("/community/me/visibility").header("Authorization", auth(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"visibilityScope\":\"URL_ONLY\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.visibilityScope").value("URL_ONLY"));
        mvc.perform(post("/community/me/pinned-sessions").header("Authorization", auth(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":" + session.getId() + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.isPinned").value(true));
        mvc.perform(get("/community/sessions/{id}", session.getId()).header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.summary").isEmpty());
        mvc.perform(patch("/community/me/hashtags/{id}/visibility", tag.hashtagId()).header("Authorization", auth(viewerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"isPublic\":false}"))
                .andExpect(status().isNotFound());
        var interest = edit.addInterest(ownerId, "123");
        mvc.perform(delete("/community/me/interests/{id}", interest.interestId())
                .header("Authorization", auth(ownerId))).andExpect(status().isOk());
    }
}
