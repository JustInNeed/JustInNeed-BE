package com.justinneed.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.community.domain.CommunityProfile;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class UnpinTest extends CommunityTestSupport {
    @Autowired CommunityCardRepository cards;

    @Test
    void unpinKeepsSessionPublicAndCardAndOtherPinOrder() throws Exception {
        var first = session(ownerId, true, "First");
        var removed = session(ownerId, true, "Removed");
        var last = session(ownerId, true, "Last");
        var profile = new CommunityProfile(ownerId);
        profile.replacePinnedSessionIds(List.of(first.getId(), removed.getId(), last.getId()));
        profiles.saveAndFlush(profile);
        mvc.perform(delete("/community/me/pinned-sessions/{id}", removed.getId())
                        .header("Authorization", auth(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.isPublic").value(true))
                .andExpect(jsonPath("$.data.isPinned").value(false));
        assertThat(removed.isPublicSession()).isTrue();
        assertThat(cards.findById(removed.getId())).isPresent();
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds())
                .containsExactly(first.getId(), last.getId());
        mvc.perform(get("/community/profiles/{id}/nodes", ownerId).header("Authorization", auth(viewerId)))
                .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test
    void unpinningAStalePinAnswersWithTheSessionsRealState() throws Exception {
        var deleted = session(ownerId, true, "Deleted");
        var hidden = session(ownerId, false, "Hidden");
        deleted.delete();
        sessions.flush();
        var profile = new CommunityProfile(ownerId);
        profile.replacePinnedSessionIds(List.of(deleted.getId(), hidden.getId()));
        profiles.saveAndFlush(profile);
        for (Long id : List.of(deleted.getId(), hidden.getId())) {
            mvc.perform(delete("/community/me/pinned-sessions/{id}", id).header("Authorization", auth(ownerId)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.isPublic").value(false));
        }
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds()).isEmpty();
    }

    @Test
    void cannotUnpinAnotherOwnersSessionOrAnUnpinnedSession() throws Exception {
        var session = session(ownerId, true, "Java");
        var profile = new CommunityProfile(ownerId);
        profile.replacePinnedSessionIds(List.of(session.getId()));
        profiles.saveAndFlush(profile);
        mvc.perform(delete("/community/me/pinned-sessions/{id}", session.getId())
                        .header("Authorization", auth(viewerId))).andExpect(status().isNotFound());
        mvc.perform(delete("/community/me/pinned-sessions/{id}", Long.MAX_VALUE)
                        .header("Authorization", auth(ownerId))).andExpect(status().isNotFound());
        assertThat(session.isPublicSession()).isTrue();
        assertThat(profiles.findById(ownerId).orElseThrow().getPinnedSessionIds()).containsExactly(session.getId());
        mvc.perform(delete("/community/me/pinned-sessions/{id}", session.getId())).andExpect(status().isUnauthorized());
    }
}
