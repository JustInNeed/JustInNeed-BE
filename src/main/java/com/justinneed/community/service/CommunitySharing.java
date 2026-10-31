package com.justinneed.community.service;

import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CommunitySharing {
    public static final Comparator<BrowsingSession> NEWEST_SESSION_FIRST = Comparator
            .comparing(CommunitySharing::lastActivityAt, Comparator.reverseOrder())
            .thenComparing(BrowsingSession::getId, Comparator.reverseOrder());

    private final CommunitySessionRepository sessions;

    public CommunitySharing(CommunitySessionRepository sessions) {
        this.sessions = sessions;
    }

    // 비공개 해시태그가 섞인 세션도 보인다. 태그는 visibleTags 로 거른다 (본문에는 그 태그 내용이 남을 수 있다)
    public List<BrowsingSession> visibleSessions(CommunityProfile profile) {
        return sessions.findPublicSessions(profile.getUserId());
    }

    // visibleSessions 의 개수
    public long publicSessionCount(CommunityProfile profile) {
        return sessions.countPublicSessions(profile.getUserId());
    }

    // 세션 id 중 지금 다른 사용자에게 보이는 것. visibleSessions 와 같은 규칙이고,
    // 프로필 화면에서는 CommunityProfiles.read 가 막는 탈퇴 회원의 세션도 여기서 뺀다
    public Set<Long> publicSessionIds(Collection<Long> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(sessions.findPublicIds(sessionIds));
    }

    // 공개 세션 id 한 페이지: 고정한 세션을 고정 순서대로 앞에, 나머지는 넘긴 순서대로.
    // 고정은 공개 세션 안에서의 자리라, ids 에 없는 고정(지운 세션 등)은 빠진다
    public PinnedPage pinnedFirst(CommunityProfile profile, List<Long> ids, Pageable paging) {
        Set<Long> ordered = new LinkedHashSet<>(profile.getPinnedSessionIds());
        ordered.retainAll(new HashSet<>(ids));
        ordered.addAll(ids);
        List<Long> found = ordered.stream().skip(paging.getOffset()).limit(paging.getPageSize() + 1L).toList();
        return new PinnedPage(found.stream().limit(paging.getPageSize()).toList(), found.size() > paging.getPageSize());
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
                owner || profile.getVisibility() == NodeVisibility.CONTENT,
                profile.getPinnedSessionIds().contains(session.getId()));
    }

    // community_cards.last_activity_at 과 같은 값
    private static LocalDateTime lastActivityAt(BrowsingSession session) {
        return session.getEndedAt() != null ? session.getEndedAt() : session.getStartedAt();
    }

    public record PinnedPage(List<Long> ids, boolean hasNext) {
    }
}
