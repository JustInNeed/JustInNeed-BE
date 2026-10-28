package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.domain.CommunityProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnpinTest extends CommunityTestSupport {
    @Test
    void unpinMakesSessionPrivateAndKeepsContentAndOtherPinOrder() throws Exception {
        var first = session(ownerId, true, "First");
        var removed = session(ownerId, true, "Removed");
        var last = session(ownerId, true, "Last");
        var profile = new CommunityProfile(ownerId);
        profile.replacePinnedSessionIds(List.of(first.getId(), removed.getId(), last.getId()));
        profiles.saveAndFlush(profile);
        mvc.perform(delete("/community/me/shared-sessions/{id}", removed.getId())
                        .header("Authorization", auth(ownerId))).andExpect(status().isOk());
        assertThat(removed.isPublicSession()).isFalse();
        assertThat(removed.getSummary().getMarkdown()).isEqualTo("private markdown");
        assertThat(sessions.findByIdAndUserIdAndDeletedAtIsNull(removed.getId(), ownerId)).isPresent();
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds())
                .containsExactly(first.getId(), last.getId());
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void cannotUnpinAnotherOwnersSessionOrAnUnpinnedSession() throws Exception {
        var session = session(ownerId, true, "Java");
        var profile = new CommunityProfile(ownerId);
        profile.replacePinnedSessionIds(List.of(session.getId()));
        profiles.saveAndFlush(profile);
        mvc.perform(delete("/community/me/shared-sessions/{id}", session.getId())
                        .header("Authorization", auth(viewerId))).andExpect(status().isNotFound());
        mvc.perform(delete("/community/me/shared-sessions/{id}", Long.MAX_VALUE)
                        .header("Authorization", auth(ownerId))).andExpect(status().isNotFound());
        assertThat(session.isPublicSession()).isTrue();
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds()).containsExactly(session.getId());
        mvc.perform(delete("/community/me/shared-sessions/{id}", session.getId())).andExpect(status().isUnauthorized());
    }
}
