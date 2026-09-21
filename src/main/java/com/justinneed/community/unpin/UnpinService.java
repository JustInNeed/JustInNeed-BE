package com.justinneed.community.unpin;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnpinService {
    private final CommunityProfiles profiles;
    private final CommunitySessionRepository sessions;

    public UnpinService(CommunityProfiles profiles, CommunitySessionRepository sessions) {
        this.profiles = profiles;
        this.sessions = sessions;
    }

    @Transactional
    public void unpin(Long userId, Long sessionId) {
        CommunityProfile profile = profiles.lock(userId);
        if (!profile.getPinnedSessionIds().contains(sessionId)) {
            throw new CustomException(ErrorCode.SESSION_NOT_FOUND);
        }
        var session = sessions.lockOwnedSession(userId, sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        var pins = new ArrayList<>(profile.getPinnedSessionIds());
        pins.remove(sessionId);
        profile.replacePinnedSessionIds(pins);
        session.update(null, null, false, null, null, null);
    }
}
