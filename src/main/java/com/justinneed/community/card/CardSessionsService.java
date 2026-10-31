package com.justinneed.community.card;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionListResponse;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카드 상세의 관련 세션과 프로필의 카드 목록. 작성자 본인이 불러도 방문자 규칙이다 (CommunityCardReader 와 같다). */
@Service
@Transactional(readOnly = true)
public class CardSessionsService {
    private static final int MAX_SESSIONS = 3;
    private static final Sort NEWEST_CARD_FIRST =
            Sort.by(Sort.Order.desc("lastActivityAt"), Sort.Order.desc("sessionId"));

    private final CommunityCardRepository cards;
    private final CommunityCardReader reader;
    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;

    public CardSessionsService(CommunityCardRepository cards, CommunityCardReader reader, CommunityProfiles profiles,
            CommunitySharing sharing) {
        this.cards = cards;
        this.reader = reader;
        this.profiles = profiles;
        this.sharing = sharing;
    }

    /** 그 세션 + 공개 태그가 겹치는 같은 작성자의 공개 세션. 비공개 해시태그는 겹쳐도 관련으로 보지 않는다. */
    public Sessions related(Long mindmapId) {
        Set<Long> publicIds = sharing.publicSessionIds(List.of(mindmapId));
        CommunityCardRow card = cards.findById(mindmapId).filter(row -> publicIds.contains(row.getSessionId()))
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        CommunityProfile owner = profiles.read(card.getOwnerId());
        List<BrowsingSession> ownerSessions = sharing.visibleSessions(owner);
        BrowsingSession anchor = ownerSessions.stream().filter(session -> session.getId().equals(mindmapId))
                .findFirst().orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        Set<String> anchorTags = publicTagKeys(owner, anchor);
        Stream<BrowsingSession> others = ownerSessions.stream()
                .filter(session -> !session.getId().equals(mindmapId))
                .filter(session -> !Collections.disjoint(anchorTags, publicTagKeys(owner, session)))
                .sorted(CommunitySharing.NEWEST_SESSION_FIRST)
                .limit(MAX_SESSIONS - 1);
        boolean content = owner.getVisibility() == NodeVisibility.CONTENT;
        return new Sessions(Stream.concat(Stream.of(anchor), others)
                .map(session -> SessionListResponse.forViewer(session, sharing.visibleTags(owner, session.getTags()), content))
                .toList());
    }

    public Mindmaps ofOwner(Long ownerId, Long viewerId, int page, int size) {
        CommunityProfile owner = profiles.read(ownerId);
        PageRequest paging = BookmarkQueryService.paging(page, size);
        var found = sharing.pinnedFirst(owner, cards.findSessionIdsByOwnerId(ownerId, NEWEST_CARD_FIRST), paging);
        Set<Long> pinned = Set.copyOf(owner.getPinnedSessionIds());
        return new Mindmaps(reader.cards(found.ids(), viewerId).stream()
                .map(card -> new ProfileCard(card, pinned.contains(card.mindmapId()))).toList(), found.hasNext());
    }

    private Set<String> publicTagKeys(CommunityProfile owner, BrowsingSession session) {
        return sharing.visibleTags(owner, session.getTags()).stream().map(CommunityTags::key).collect(Collectors.toSet());
    }

    public record Sessions(List<SessionListResponse> sessions) {
    }

    public record Mindmaps(List<ProfileCard> mindmaps, boolean hasNext) {
    }

    /** 프로필 카드 = 커뮤니티 카드 + 고정 여부. 피드 · 검색의 CommunityCard 에는 고정이 없다. */
    public record ProfileCard(@JsonUnwrapped CommunityCard card, boolean isPinned) {
    }
}
