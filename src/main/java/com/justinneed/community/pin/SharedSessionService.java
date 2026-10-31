package com.justinneed.community.pin;

import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 고정은 공개 세션 안에서 위에 두는 자리다. 공개 여부는 바꾸지 않는다 (공개는 세션 상세의 PATCH /sessions/{id} 로만). */
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
    public void pin(Long userId, Long sessionId) {
        CommunityProfile profile = profiles.lock(userId);
        BrowsingSession session = sessions.lockOwnedSession(userId, sessionId)
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        if (session.getStatus() != SessionStatus.COMPLETED) {
            throw new CustomException(ErrorCode.SESSION_NOT_COMPLETED);
        }
        if (!session.isPublicSession()) {
            throw new CustomException(ErrorCode.PIN_REQUIRES_PUBLIC);
        }
        List<Long> pins = new ArrayList<>(livePins(profile).stream().map(BrowsingSession::getId).toList());
        if (!pins.contains(sessionId)) {
            pins.add(sessionId);
        }
        profile.replacePinnedSessionIds(pins);
    }

    // 공개 세션 전체: 고정 순서대로 먼저, 나머지는 최근 활동순
    public SharedSessions list(Long userId, Long viewerId, int page, int size) {
        CommunityProfile profile = profiles.read(userId);
        PageRequest paging = BookmarkQueryService.paging(page, size);
        List<BrowsingSession> visible = sharing.visibleSessions(profile);
        Map<Long, BrowsingSession> byId = visible.stream()
                .collect(Collectors.toMap(BrowsingSession::getId, Function.identity()));
        List<Long> newest = visible.stream().sorted(CommunitySharing.NEWEST_SESSION_FIRST)
                .map(BrowsingSession::getId).toList();
        var found = sharing.pinnedFirst(profile, newest, paging);
        return new SharedSessions(found.ids().stream()
                .map(id -> sharing.project(profile, viewerId, byId.get(id))).toList(), found.hasNext());
    }

    // 지금 살아 있는 고정만
    public List<SharedSessionView> pinned(Long userId, Long viewerId) {
        CommunityProfile profile = profiles.read(userId);
        return livePins(profile).stream()
                .map(session -> sharing.project(profile, viewerId, session)).toList();
    }

    public SharedSessionView detail(Long userId, Long viewerId, Long sessionId) {
        CommunityProfile profile = profiles.read(userId);
        BrowsingSession session = sharing.visibleSessions(profile).stream()
                .filter(candidate -> candidate.getId().equals(sessionId)).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        return sharing.project(profile, viewerId, session);
    }

    @Transactional
    public void reorder(Long userId, List<Long> sessionIds) {
        CommunityProfile profile = profiles.lock(userId);
        List<Long> current = livePins(profile).stream().map(BrowsingSession::getId).toList();
        // 크기까지 같아야 중복 id 가 걸러진다
        if (current.size() != sessionIds.size()
                || !new HashSet<>(current).equals(new HashSet<>(sessionIds))) {
            throw new CustomException(ErrorCode.INVALID_PIN_ORDER);
        }
        profile.replacePinnedSessionIds(sessionIds);
    }

    private List<BrowsingSession> livePins(CommunityProfile profile) {
        Map<Long, BrowsingSession> visible = sharing.visibleSessions(profile).stream()
                .collect(Collectors.toMap(BrowsingSession::getId, Function.identity()));
        // 세션 삭제는 고정 목록을 고치지 않으므로 읽을 때 거른다
        return profile.getPinnedSessionIds().stream().filter(visible::containsKey).map(visible::get).toList();
    }

    public record SharedSessions(List<SharedSessionView> sessions, boolean hasNext) {
    }
}
