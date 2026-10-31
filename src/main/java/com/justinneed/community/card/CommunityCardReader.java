package com.justinneed.community.card;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.card.CommunityCard.Owner;
import com.justinneed.community.card.CommunityCard.RelatedUrl;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.repository.CommunityMemberRepository;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.community.service.CommunitySharing;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * community_cards → 카드 응답. 몇 장을 읽든 카드 · 회원 · 프로필을 한 번씩만 조회한다.
 * 작성자 본인이 봐도 방문자 규칙을 쓴다 (PublicGraphService 와 같다). 탈퇴한 작성자의 카드는 나오지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class CommunityCardReader {
    private final CommunityCardRepository cards;
    private final CommunityMemberRepository members;
    private final CommunityProfileRepository profiles;
    private final CommunitySharing sharing;
    private final CardCounts counts;

    public CommunityCardReader(CommunityCardRepository cards, CommunityMemberRepository members,
            CommunityProfileRepository profiles, CommunitySharing sharing, CardCounts counts) {
        this.cards = cards;
        this.members = members;
        this.profiles = profiles;
        this.sharing = sharing;
        this.counts = counts;
    }

    /** 넘긴 순서대로. 공개 카드가 아닌 id 는 빠진다. */
    public List<CommunityCard> cards(Collection<Long> sessionIds, Long viewerId) {
        if (sessionIds.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = new LinkedHashSet<>(sessionIds);
        Map<Long, CommunityCardRow> rows = cards.findAllById(ids).stream()
                .collect(Collectors.toMap(CommunityCardRow::getSessionId, Function.identity()));
        Set<Long> ownerIds = rows.values().stream().map(CommunityCardRow::getOwnerId).collect(Collectors.toSet());
        Map<Long, String> nicknames = new HashMap<>();
        for (Member member : members.findActiveByIdIn(ownerIds)) {
            nicknames.put(member.getId(), member.getNickname());
        }
        Map<Long, CommunityProfile> owners = profiles.findAllById(nicknames.keySet()).stream()
                .collect(Collectors.toMap(CommunityProfile::getUserId, Function.identity()));

        List<CommunityCardRow> visible = new ArrayList<>();
        for (Long id : ids) {
            CommunityCardRow row = rows.get(id);
            if (row != null && nicknames.containsKey(row.getOwnerId())) {
                visible.add(row);
            }
        }
        Map<Long, CardCounts.Count> numbers =
                counts.counts(visible.stream().map(CommunityCardRow::getSessionId).toList(), viewerId);
        return visible.stream().map(row -> card(row, nicknames.get(row.getOwnerId()),
                owners.getOrDefault(row.getOwnerId(), new CommunityProfile(row.getOwnerId())),
                numbers.getOrDefault(row.getSessionId(), CardCounts.Count.ZERO))).toList();
    }

    public Optional<CommunityCard> card(Long sessionId, Long viewerId) {
        return cards(List.of(sessionId), viewerId).stream().findFirst();
    }

    /** 작성자 카드 전체의 합계. 카드마다 응답하는 nodeCount · exploringCount 를 더한 값이다. */
    public OwnerTotals totals(CommunityProfile owner, Long viewerId) {
        boolean content = owner.getVisibility() == NodeVisibility.CONTENT;
        List<CommunityCardRow> rows = cards.findByOwnerIdInOrderBySessionIdDesc(List.of(owner.getUserId()));
        long exploringCount = counts.counts(rows.stream().map(CommunityCardRow::getSessionId).toList(), viewerId)
                .values().stream().mapToLong(CardCounts.Count::exploringCount).sum();
        return new OwnerTotals(rows.stream().mapToLong(row -> nodeCount(row, content)).sum(), exploringCount);
    }

    private static long nodeCount(CommunityCardRow row, boolean content) {
        return content ? row.getAiNodeCount() : row.getUrlCount();
    }

    private CommunityCard card(CommunityCardRow row, String nickname, CommunityProfile owner, CardCounts.Count count) {
        boolean content = owner.getVisibility() == NodeVisibility.CONTENT;
        return new CommunityCard(row.getSessionId(), new Owner(row.getOwnerId(), nickname),
                content ? row.getTitle() : null,
                content ? row.getSnippet() : null,
                sharing.visibleTags(owner, row.getHashtags()),
                nodeCount(row, content),
                count.likeCount(), count.likedByMe(), count.bookmarkCount(), count.bookmarkId(),
                count.exploringCount(), count.exploringByMe(), row.getLastActivityAt(),
                row.getUrls().stream().map(url -> new RelatedUrl(content ? url.title() : null, url.url())).toList());
    }

    public record OwnerTotals(long nodeCount, long exploringCount) {
    }
}
