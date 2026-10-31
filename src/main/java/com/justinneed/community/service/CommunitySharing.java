package com.justinneed.community.service;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CommunitySharing {
    private final CommunitySessionRepository sessions;

    public CommunitySharing(CommunitySessionRepository sessions) {
        this.sessions = sessions;
    }

    // 비공개 해시태그가 섞인 세션도 보인다. 태그는 visibleTags 로 거른다 (본문에는 그 태그 내용이 남을 수 있다)
    public List<BrowsingSession> visibleSessions(CommunityProfile profile) {
        return sessions.findPublicSessions(profile.getUserId());
    }

    // 세션 id 중 지금 다른 사용자에게 보이는 것. visibleSessions 와 같은 규칙이고,
    // 프로필 화면에서는 CommunityProfiles.read 가 막는 탈퇴 회원의 세션도 여기서 뺀다
    public Set<Long> publicSessionIds(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(sessions.findPublicIds(sessionIds));
    }

    // 방문자에게 보이는 태그
    public List<String> visibleTags(CommunityProfile profile, List<String> tags) {
        return tags.stream().filter(tag -> !isHidden(profile, tag)).toList();
    }

    public boolean isHidden(CommunityProfile profile, String tag) {
        return profile.getHiddenHashtags().stream().anyMatch(hidden -> hidden.equalsIgnoreCase(tag));
    }

    public SharedSessionView project(CommunityProfile profile, Long viewerId, BrowsingSession session) {
        boolean owner = profile.getUserId().equals(viewerId);
        return SharedSessionView.from(session, owner ? session.getTags() : visibleTags(profile, session.getTags()),
                owner || profile.getVisibility() == NodeVisibility.CONTENT);
    }
}
