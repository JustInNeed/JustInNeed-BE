package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class SharedSessionTest extends CommunityTestSupport {
    private void pin(Long id) throws Exception {
        mvc.perform(post("/community/me/shared-sessions").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":" + id + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void pinIsOwnedAtomicAndIdempotentAndPreservesPrivateContentPolicy() throws Exception {
        var session = session(ownerId, false, "Java");
        pin(session.getId());
        pin(session.getId());
        assertThat(session.isPublicSession()).isTrue();
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds()).containsExactly(session.getId());
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, session.getId())
                        .header("Authorization", auth(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.markdown").doesNotExist())
                .andExpect(jsonPath("$.data.sources").doesNotExist());
        mvc.perform(post("/community/me/shared-sessions").header("Authorization", auth(viewerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":" + session.getId() + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void ordersOnlyTheExactCurrentPinSet() throws Exception {
        var first = session(ownerId, false, "First");
        var second = session(ownerId, false, "Second");
        pin(first.getId());
        pin(second.getId());
        mvc.perform(patch("/community/me/shared-sessions/order").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIds\":[" + second.getId() + "," + first.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(second.getId()));
        for (String ids : new String[]{"[]", "[" + first.getId() + "," + first.getId() + "]",
                "[null]", "[" + Long.MAX_VALUE + "]"}) {
            mvc.perform(patch("/community/me/shared-sessions/order").header("Authorization", auth(ownerId))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"sessionIds\":" + ids + "}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds())
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void detailCannotBypassHiddenTagsUnpinnedPrivateOrDeletedState() throws Exception {
        var hidden = session(ownerId, true, "Secret", "Public");
        var privateSession = session(ownerId, false, "Private");
        var deleted = session(ownerId, true, "Deleted");
        var unpinned = session(ownerId, true, "Unpinned");
        deleted.delete();
        var profile = new CommunityProfile(ownerId);
        profile.changeVisibility(NodeVisibility.CONTENT);
        profile.replaceHiddenHashtags(List.of("secret"));
        profile.replacePinnedSessionIds(List.of(hidden.getId(), privateSession.getId(), deleted.getId()));
        profiles.saveAndFlush(profile);
        for (Long id : List.of(hidden.getId(), privateSession.getId(), deleted.getId(), unpinned.getId())) {
            mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, id)
                            .header("Authorization", auth(viewerId))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/community/profiles/{id}/shared-sessions", ownerId)
                        .header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, hidden.getId())
                        .header("Authorization", auth(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.markdown").value("private markdown"));
    }

    @Test
    void rejectsDeletedSessionAndUnauthenticatedPin() throws Exception {
        var deleted = session(ownerId, false, "Deleted");
        deleted.delete();
        sessions.flush();
        String body = "{\"sessionId\":" + deleted.getId() + "}";
        mvc.perform(post("/community/me/shared-sessions").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/community/me/shared-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }
}
