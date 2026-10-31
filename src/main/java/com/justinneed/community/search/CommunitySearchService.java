package com.justinneed.community.search;

import com.justinneed.auth.domain.Member;
import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.community.card.CommunityCardRepository;
import com.justinneed.community.card.CommunityCardRow;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunityMemberRepository;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.community.service.CommunitySharing;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 커뮤니티 검색. 검색어는 앞 # · 앞뒤 공백을 떼고 대소문자 무시 포함 검색한다.
 * SQL 로 후보를 좁히고 비공개 해시태그는 여기서 걸러, 페이지는 실제로 보이는 것만 세어 나눈다.
 * 내 카드 · 나도 결과에 나온다.
 */
@Service
@Transactional(readOnly = true)
public class CommunitySearchService {
    // 한 번에 읽는 후보 수. 최대 페이지 크기(100)보다 커서 보통 한 번으로 끝난다
    private static final int BATCH_SIZE = 200;
    private static final int PROFILE_HASHTAGS = 5;
    private static final int MAX_HASHTAG_LIMIT = 20;

    private final PublicCardSearch search;
    private final CommunityCardRepository cards;
    private final CommunityCardReader reader;
    private final CommunityMemberRepository members;
    private final CommunityProfileRepository profiles;
    private final CommunitySharing sharing;

    public CommunitySearchService(PublicCardSearch search, CommunityCardRepository cards, CommunityCardReader reader,
            CommunityMemberRepository members, CommunityProfileRepository profiles, CommunitySharing sharing) {
        this.search = search;
        this.cards = cards;
        this.reader = reader;
        this.members = members;
        this.profiles = profiles;
        this.sharing = sharing;
    }

    /** 최근 활동순 → id 큰 순. */
    public Mindmaps mindmaps(Long viewer, String q, int page, int size) {
        String key = keyword(q);
        var found = new Window<Long>(BookmarkQueryService.paging(page, size).getOffset(), size);
        for (int batch = 0; !found.isFull(); batch++) {
            var hits = search.mindmaps(key, batch, BATCH_SIZE);
            var owners = owners(hits.stream().map(PublicCardSearch.MindmapHit::ownerId).toList());
            hits.stream().filter(hit -> hit.matchedText() || hasTag(visible(owners, hit.ownerId(), hit.hashtags()), key))
                    .forEach(hit -> found.offer(hit.sessionId()));
            if (hits.size() < BATCH_SIZE) {
                break;
            }
        }
        return new Mindmaps(reader.cards(found.items(), viewer), found.hasNext());
    }

    /** 카드가 있는 회원, id 순. */
    public Profiles profiles(String q, int page, int size) {
        String key = keyword(q);
        var found = new Window<ProfileItem>(BookmarkQueryService.paging(page, size).getOffset(), size);
        long after = 0;
        while (!found.isFull()) {
            var ids = search.owners(key, after, BATCH_SIZE);
            if (ids.isEmpty()) {
                break;
            }
            Map<Long, String> nicknames = new HashMap<>();
            for (Member member : members.findActiveByIdIn(ids)) {
                nicknames.put(member.getId(), member.getNickname());
            }
            var rows = cards.findByOwnerIdInOrderBySessionIdDesc(ids).stream()
                    .collect(Collectors.groupingBy(CommunityCardRow::getOwnerId));
            var owners = owners(ids);
            for (Long id : ids) {
                var ownerCards = rows.getOrDefault(id, List.of()).stream()
                        .map(row -> visible(owners, id, row.getHashtags())).toList();
                String nickname = nicknames.get(id);
                boolean matched = nickname != null && CommunityTags.key(nickname).contains(key)
                        || ownerCards.stream().anyMatch(tags -> hasTag(tags, key));
                if (matched && !ownerCards.isEmpty()) {
                    found.offer(new ProfileItem(id, nickname, topTags(ownerCards), ownerCards.size()));
                }
            }
            after = ids.get(ids.size() - 1);
        }
        return new Profiles(found.items(), found.hasNext());
    }

    /** 검색어가 든 태그와 그 태그를 단 공개 카드 수. 많은 순 → 이름 순. */
    public Hashtags hashtags(String q, int limit) {
        String key = keyword(q);
        if (limit < 1 || limit > MAX_HASHTAG_LIMIT) {
            throw CommunityErrors.invalid("limit 은 1~" + MAX_HASHTAG_LIMIT + " 사이여야 합니다.");
        }
        var hits = search.tagged(List.of(key), null);
        var owners = owners(hits.stream().map(PublicCardSearch.TaggedCard::ownerId).toList());
        var tally = new TagTally();
        hits.forEach(hit -> tally.add(visible(owners, hit.ownerId(), hit.hashtags()).stream()
                .filter(tag -> CommunityTags.key(tag).contains(key)).toList()));
        return new Hashtags(tally.top(limit));
    }

    private static String keyword(String q) {
        // Postgres 문자열에는 NUL 을 넣을 수 없다
        String key = q.replace("\0", "").strip().replaceFirst("^#+", "").strip();
        if (key.isEmpty()) {
            throw CommunityErrors.invalid("검색어를 입력해 주세요.");
        }
        return CommunityTags.key(key);
    }

    private Map<Long, CommunityProfile> owners(Collection<Long> ids) {
        return profiles.findAllById(new HashSet<>(ids)).stream()
                .collect(Collectors.toMap(CommunityProfile::getUserId, Function.identity()));
    }

    private List<String> visible(Map<Long, CommunityProfile> owners, Long ownerId, List<String> tags) {
        return sharing.visibleTags(owners.getOrDefault(ownerId, new CommunityProfile(ownerId)), tags);
    }

    private static boolean hasTag(List<String> tags, String key) {
        return tags.stream().anyMatch(tag -> CommunityTags.key(tag).contains(key));
    }

    private static List<String> topTags(List<List<String>> ownerCards) {
        var tally = new TagTally();
        ownerCards.forEach(tally::add);
        return tally.top(PROFILE_HASHTAGS).stream().map(TagCount::tag).toList();
    }

    // 앞을 건너뛰고 size + 1 개까지 모은다. 하나 더 있으면 hasNext
    private static final class Window<T> {
        private long skip;
        private final int size;
        private final List<T> taken = new ArrayList<>();

        Window(long skip, int size) {
            this.skip = skip;
            this.size = size;
        }

        void offer(T item) {
            if (skip > 0) {
                skip--;
            } else if (!isFull()) {
                taken.add(item);
            }
        }

        boolean isFull() {
            return taken.size() > size;
        }

        List<T> items() {
            return taken.stream().limit(size).toList();
        }

        boolean hasNext() {
            return isFull();
        }
    }

    public record ProfileItem(Long userId, String nickname, List<String> hashtags, long mindmapCount) { }
    public record TagCount(String tag, long count) { }
    public record Profiles(List<ProfileItem> profiles, boolean hasNext) { }
    public record Mindmaps(List<CommunityCard> mindmaps, boolean hasNext) { }
    public record Hashtags(List<TagCount> hashtags) { }
}
