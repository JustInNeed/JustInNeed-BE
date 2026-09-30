package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class VisibilityTest extends CommunityTestSupport {
    @Test
    void defaultsToUrlsAndDoesNotLeakContentOrPrivateSessions() throws Exception {
        session(ownerId, true, "Java");
        session(ownerId, false, "Secret");
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sessions[0].urls[0]").value("https://example.com"))
                .andExpect(jsonPath("$.data[0].sessions[0].title").doesNotExist())
                .andExpect(jsonPath("$.data[0].sessions[0].markdown").doesNotExist())
                .andExpect(jsonPath("$.data[0].sessions[0].sources").doesNotExist())
                .andExpect(jsonPath("$.data[0].sessions[0].insights").doesNotExist());
        assertThat(profiles.count()).isZero();
    }

    @Test
    void contentOptInAndOwnerPreviewRespectCurrentSetting() throws Exception {
        session(ownerId, true, "Java");
        mvc.perform(patch("/community/me/visibility").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"CONTENT\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data[0].sessions[0].markdown").value("private markdown"));
        mvc.perform(patch("/community/me/visibility").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"visibility\":\"URL_ONLY\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data[0].sessions[0].markdown").doesNotExist());
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(ownerId)))
                .andExpect(jsonPath("$.data[0].sessions[0].markdown").value("private markdown"));
    }

    @Test
    void requiresAuthenticationAndValidVisibility() throws Exception {
        mvc.perform(patch("/community/me/visibility").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"CONTENT\"}")).andExpect(status().isUnauthorized());
        for (String body : new String[]{"{}", "{\"visibility\":\"invalid\"}"}) {
            mvc.perform(patch("/community/me/visibility").header("Authorization", auth(ownerId))
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/community/profiles/{id}/nodes", Long.MAX_VALUE)
                        .header("Authorization", auth(viewerId))).andExpect(status().isNotFound());
    }
}
