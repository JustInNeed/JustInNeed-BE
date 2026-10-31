package com.justinneed.community.search;

import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.community.card.CommunityCardRow;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.repository.CommunityMemberRepository;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.community.service.CommunitySharing;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 카드가 있거나 프로필이 있는 회원을 id 순으로 훑는다. community_cards 를 100명씩 묶어 읽고, 쓰기 · 잠금은 없다. */
@Service
@Transactional(readOnly = true)
public class PublicDirectory {
    private final CommunityDirectoryRepository directory;
    private final CommunityCardRepository cards;
    private final CommunityMemberRepository members;
    private final CommunityProfileRepository profiles;
    private final CommunitySharing sharing;
    public PublicDirectory(CommunityDirectoryRepository directory, CommunityCardRepository cards,
            CommunityMemberRepository members, CommunityProfileRepository profiles, CommunitySharing sharing) {
        this.directory = directory; this.cards = cards; this.members = members;
        this.profiles = profiles; this.sharing = sharing;
    }
    public void visit(Predicate<PublicOwner> visitor) {
        long after = 0;
        while (true) {
            var ids = directory.candidates(after, PageRequest.of(0, 100));
            if (ids.isEmpty()) return;
            for (var owner : load(ids)) {
                if (!visitor.test(owner)) return;
            }
            after = ids.get(ids.size() - 1);
        }
    }
    private List<PublicOwner> load(List<Long> ids) {
        var nicknames = new HashMap<Long, String>();
        members.findActiveByIdIn(ids).forEach(m -> nicknames.put(m.getId(), m.getNickname()));
        var rows = cards.findByOwnerIdInOrderBySessionIdDesc(ids).stream()
                .collect(Collectors.groupingBy(CommunityCardRow::getOwnerId));
        var owners = profiles.findAllById(ids).stream()
                .collect(Collectors.toMap(CommunityProfile::getUserId, Function.identity()));
        return ids.stream().filter(nicknames::containsKey).map(id -> owner(id, nicknames.get(id),
                owners.getOrDefault(id, new CommunityProfile(id)), rows.getOrDefault(id, List.of()))).toList();
    }
    private PublicOwner owner(Long id, String nickname, CommunityProfile profile, List<CommunityCardRow> rows) {
        boolean content = profile.getVisibility() == NodeVisibility.CONTENT;
        var cards = rows.stream().map(row -> new OwnerCard(sharing.visibleTags(profile, row.getHashtags()),
                content ? row.getAiNodeCount() : row.getUrlCount(),
                row.getUrls().stream().map(url -> url.url()).toList())).toList();
        return new PublicOwner(id, nickname, sharing.visibleTags(profile, profile.getInterests()), cards);
    }

    public record OwnerCard(List<String> hashtags, int nodeCount, List<String> urls) { }
    public record PublicOwner(Long ownerId, String nickname, List<String> interests, List<OwnerCard> cards) {
        public long nodeCount() { return cards.stream().mapToLong(OwnerCard::nodeCount).sum(); }
        /** 노드가 있는 카드의 태그 중 keys(소문자)에 든 것. 대소문자 무시 중복 제거, 처음 나온 표기. */
        public List<String> cardTags(Set<String> keys) {
            var names = new LinkedHashMap<String, String>();
            cards.stream().filter(c -> c.nodeCount() > 0).flatMap(c -> c.hashtags().stream())
                    .filter(t -> keys.contains(CommunityTags.key(t))).forEach(t -> names.putIfAbsent(CommunityTags.key(t), t));
            return List.copyOf(names.values());
        }
    }
}
