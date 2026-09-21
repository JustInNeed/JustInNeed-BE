package com.justinneed.community.service;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CommunitySharing {
    private final CommunitySessionRepository sessions;

    public CommunitySharing(CommunitySessionRepository sessions) {
        this.sessions = sessions;
    }

    public List<BrowsingSession> visibleSessions(CommunityProfile profile, Long viewerId) {
        boolean owner = profile.getUserId().equals(viewerId);
        return sessions.findPublicSessions(profile.getUserId()).stream()
                // A mixed-tag session can reveal a hidden node through its body or sources.
                .filter(session -> owner || session.getTags().stream().noneMatch(tag -> isHidden(profile, tag)))
                .toList();
    }

    public boolean isHidden(CommunityProfile profile, String tag) {
        return profile.getHiddenHashtags().stream().anyMatch(hidden -> hidden.equalsIgnoreCase(tag));
    }

    public SharedSessionView project(CommunityProfile profile, Long viewerId, BrowsingSession session) {
        return SharedSessionView.from(session,
                profile.getUserId().equals(viewerId) || profile.getVisibility() == NodeVisibility.CONTENT);
    }
}
