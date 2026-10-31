package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class SharedSessionTest extends CommunityTestSupport {
    @Autowired CommunityCardRepository cards;

    private ResultActions pin(Long id) throws Exception {
        return mvc.perform(post("/community/me/pinned-sessions").header("Authorization", auth(ownerId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":" + id + "}"));
    }

    @Test
    void pinIsOwnedAndIdempotentAndKeepsTheSessionPublicAndContentPolicy() throws Exception {
        var session = session(ownerId, true, "Java");
        pin(session.getId()).andExpect(status().isCreated());
        pin(session.getId()).andExpect(status().isCreated());
        assertThat(session.isPublicSession()).isTrue();
        assertThat(cards.findById(session.getId())).isPresent();
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds()).containsExactly(session.getId());
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, session.getId())
                        .header("Authorization", auth(viewerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isPinned").value(true))
                .andExpect(jsonPath("$.data.markdown").doesNotExist())
                .andExpect(jsonPath("$.data.sources").doesNotExist());
        mvc.perform(post("/community/me/pinned-sessions").header("Authorization", auth(viewerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":" + session.getId() + "}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void pinRefusesAPrivateSessionAndLeavesItPrivate() throws Exception {
        var session = session(ownerId, false, "Java");
        pin(session.getId()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("공개한 세션만 고정할 수 있습니다."));
        assertThat(session.isPublicSession()).isFalse();
        assertThat(cards.findById(session.getId())).isEmpty();
        assertThat(profiles.findById(ownerId).map(CommunityProfile::getPinnedSessionIds).orElse(List.of())).isEmpty();
    }

    @Test
    void ordersOnlyTheExactCurrentPinSet() throws Exception {
        var first = session(ownerId, true, "First");
        var second = session(ownerId, true, "Second");
        pin(first.getId());
        pin(second.getId());
        mvc.perform(patch("/community/me/pinned-sessions/order").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIds\":[" + second.getId() + "," + first.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionIds[0]").value(second.getId()));
        for (String ids : new String[]{"[]", "[" + first.getId() + "," + first.getId() + "]",
                "[" + first.getId() + "," + second.getId() + "," + first.getId() + "]",
                "[" + Long.MAX_VALUE + "]"}) {
            mvc.perform(patch("/community/me/pinned-sessions/order").header("Authorization", auth(ownerId))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"sessionIds\":" + ids + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("고정할 세션 목록이 올바르지 않습니다."));
        }
        mvc.perform(patch("/community/me/pinned-sessions/order").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sessionIds\":[null]}"))
                .andExpect(status().isBadRequest());
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds())
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    void detailFollowsPublicStateAndHidesOnlyTheTag() throws Exception {
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
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, hidden.getId())
                        .header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.tags[*]").value(org.hamcrest.Matchers.contains("Public")));
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, unpinned.getId())
                        .header("Authorization", auth(viewerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.isPinned").value(false));
        for (Long id : List.of(privateSession.getId(), deleted.getId())) {
            mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, id)
                            .header("Authorization", auth(viewerId))).andExpect(status().isNotFound());
        }
        mvc.perform(get("/community/profiles/{id}/shared-sessions", ownerId)
                        .header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.sessions.length()").value(2))
                .andExpect(jsonPath("$.data.sessions[0].id").value(hidden.getId()))
                .andExpect(jsonPath("$.data.sessions[1].id").value(unpinned.getId()));
        mvc.perform(get("/community/profiles/{id}/shared-sessions/{sessionId}", ownerId, hidden.getId())
                        .header("Authorization", auth(ownerId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.markdown").value("private markdown"));
    }

    @Test
    void rejectsDeletedSessionAndUnauthenticatedPin() throws Exception {
        var deleted = session(ownerId, true, "Deleted");
        deleted.delete();
        sessions.flush();
        String body = "{\"sessionId\":" + deleted.getId() + "}";
        mvc.perform(post("/community/me/pinned-sessions").header("Authorization", auth(ownerId))
                        .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post("/community/me/pinned-sessions").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oldPinPathsAreGone() throws Exception {
        var session = session(ownerId, true, "Java");
        pin(session.getId()).andExpect(status().isCreated());
        String body = "{\"sessionId\":" + session.getId() + "}";
        var requests = List.of(
                post("/community/me/shared-sessions").contentType(MediaType.APPLICATION_JSON).content(body),
                patch("/community/me/shared-sessions/order").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionIds\":[" + session.getId() + "]}"),
                delete("/community/me/shared-sessions/{id}", session.getId()));
        for (var request : requests) {
            mvc.perform(request.header("Authorization", auth(ownerId))).andExpect(status().isNotFound());
        }
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds()).containsExactly(session.getId());
    }
}
