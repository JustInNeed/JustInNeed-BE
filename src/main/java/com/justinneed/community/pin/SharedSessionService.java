package com.justinneed.community.pin;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.BrowsingSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class SharedSessionService {
    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;
    private final CommunitySessionRepository sessions;

    public SharedSessionService(CommunityProfiles profiles, CommunitySharing sharing,
            CommunitySessionRepository sessions) {
        this.profiles = profiles;
        this.sharing = sharing;
        this.sessions = sessions;
    }

    @Transactional
    public SharedSessionView pin(Long userId, Long sessionId) {
        CommunityProfile profile = profiles.lock(userId);
        BrowsingSession session = sessions.lockOwnedSession(userId, sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        List<Long> pins = new ArrayList<>(livePins(profile, userId).stream().map(BrowsingSession::getId).toList());
        if (!pins.contains(sessionId)) {
            pins.add(sessionId);
        }
        session.update(null, null, true, null, null, null);
        profile.replacePinnedSessionIds(pins);
        return sharing.project(profile, userId, session);
    }

    public List<SharedSessionView> list(Long userId, Long viewerId) {
        CommunityProfile profile = profiles.read(userId);
        return livePins(profile, viewerId).stream()
                .map(session -> sharing.project(profile, viewerId, session)).toList();
    }

    public SharedSessionView detail(Long userId, Long viewerId, Long sessionId) {
        CommunityProfile profile = profiles.read(userId);
        BrowsingSession session = livePins(profile, viewerId).stream()
                .filter(candidate -> candidate.getId().equals(sessionId)).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        return sharing.project(profile, viewerId, session);
    }

    @Transactional
    public List<SharedSessionView> reorder(Long userId, List<Long> sessionIds) {
        CommunityProfile profile = profiles.lock(userId);
        List<Long> current = livePins(profile, userId).stream().map(BrowsingSession::getId).toList();
        if (current.size() != sessionIds.size()
                || !new HashSet<>(current).equals(new HashSet<>(sessionIds))) {
            throw new CustomException(ErrorCode.INVALID_ORDER_REQUEST);
        }
        profile.replacePinnedSessionIds(sessionIds);
        return list(userId, userId);
    }

    private List<BrowsingSession> livePins(CommunityProfile profile, Long viewerId) {
        Map<Long, BrowsingSession> visible = sharing.visibleSessions(profile, viewerId).stream()
                .collect(Collectors.toMap(BrowsingSession::getId, Function.identity()));
        // Session edits/deletions can revoke sharing without going through this controller.
        return profile.getPinnedSessionIds().stream().filter(visible::containsKey).map(visible::get).toList();
    }
}
