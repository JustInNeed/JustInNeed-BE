package com.justinneed.community.service;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.PublicSessionRow;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CommunitySharing {
    private final CommunitySessionRepository sessions;
    private final CommunityProfileRepository profiles;

    public CommunitySharing(CommunitySessionRepository sessions, CommunityProfileRepository profiles) {
        this.sessions = sessions;
        this.profiles = profiles;
    }

    public List<BrowsingSession> visibleSessions(CommunityProfile profile, Long viewerId) {
        boolean owner = profile.getUserId().equals(viewerId);
        return sessions.findPublicSessions(profile.getUserId()).stream()
                // A mixed-tag session can reveal a hidden node through its body or sources.
                .filter(session -> owner || hasNoHiddenTag(profile, session.getTags()))
                .toList();
    }

    // 세션 id 중 지금 다른 사용자에게 보이는 것. visibleSessions 의 방문자 규칙과 같고,
    // 프로필 화면에서는 CommunityProfiles.read 가 막는 탈퇴 회원의 세션도 여기서 뺀다
    public Set<Long> publicSessionIds(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        List<PublicSessionRow> rows = sessions.findPublicRows(sessionIds);
        Map<Long, CommunityProfile> owners = profiles
                .findAllById(rows.stream().map(PublicSessionRow::userId).distinct().toList()).stream()
                .collect(Collectors.toMap(CommunityProfile::getUserId, Function.identity()));
        return rows.stream()
                .filter(row -> !owners.containsKey(row.userId()) || hasNoHiddenTag(owners.get(row.userId()), row.tags()))
                .map(PublicSessionRow::id)
                .collect(Collectors.toSet());
    }

    private boolean hasNoHiddenTag(CommunityProfile profile, List<String> tags) {
        return tags.stream().noneMatch(tag -> isHidden(profile, tag));
    }

    public boolean isHidden(CommunityProfile profile, String tag) {
        return profile.getHiddenHashtags().stream().anyMatch(hidden -> hidden.equalsIgnoreCase(tag));
    }

    public SharedSessionView project(CommunityProfile profile, Long viewerId, BrowsingSession session) {
        return SharedSessionView.from(session,
                profile.getUserId().equals(viewerId) || profile.getVisibility() == NodeVisibility.CONTENT);
    }
}
