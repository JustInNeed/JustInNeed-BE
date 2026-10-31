package com.justinneed.community.recommend;

import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.card.CommunityCard;
import com.justinneed.community.card.CommunityCardReader;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.repository.CommunityProfileRepository;
import com.justinneed.community.search.PublicCardSearch;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추천 카드. 공개 세션 1개 = 카드 1장이고 내 카드는 뺀다.
 * 카드의 공개 태그와 기준 태그가 온전히(대소문자 무시) 겹치는 수가 많은 순 → 최근 활동순 → id 큰 순.
 * 이미 저장한 카드를 빼지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class RecommendationService {
    private static final int MAX_BASIS = 10;
    private static final Comparator<Ranked> RANKING = Comparator.comparingLong(Ranked::matches).reversed()
            .thenComparing(Ranked::lastActivityAt, Comparator.reverseOrder())
            .thenComparing(Ranked::sessionId, Comparator.reverseOrder());

    private final CommunityProfiles profiles;
    private final CommunityProfileRepository profileRows;
    private final BookmarkQueryService bookmarks;
    private final PublicCardSearch search;
    private final CommunityCardReader reader;
    private final CommunitySharing sharing;
    public RecommendationService(CommunityProfiles profiles, CommunityProfileRepository profileRows,
            BookmarkQueryService bookmarks, PublicCardSearch search, CommunityCardReader reader, CommunitySharing sharing) {
        this.profiles = profiles; this.profileRows = profileRows; this.bookmarks = bookmarks;
        this.search = search; this.reader = reader; this.sharing = sharing;
    }
    public Recommendations interests(Long actor, int limit) {
        return recommend(actor, profiles.read(actor).getInterests(), limit);
    }
    public Recommendations recent(Long actor, int limit) {
        profiles.read(actor);
        return recommend(actor, bookmarks.recentHashtags(actor), limit);
    }
    private Recommendations recommend(Long actor, List<String> basis, int limit) {
        checkLimit(limit);
        var unique = new LinkedHashMap<String, String>();
        basis.stream().filter(t -> unique.size() < MAX_BASIS || unique.containsKey(CommunityTags.key(t)))
                .forEach(t -> unique.putIfAbsent(CommunityTags.key(t), t));
        if (unique.isEmpty()) return new Recommendations(List.of(), List.of());
        var hits = search.tagged(List.copyOf(unique.keySet()), actor);
        var owners = profileRows.findAllById(hits.stream().map(PublicCardSearch.TaggedCard::ownerId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(CommunityProfile::getUserId, Function.identity()));
        var ranked = hits.stream().map(hit -> {
            var visible = sharing.visibleTags(owners.getOrDefault(hit.ownerId(), new CommunityProfile(hit.ownerId())), hit.hashtags());
            long matches = visible.stream().map(CommunityTags::key).distinct().filter(unique::containsKey).count();
            return new Ranked(hit.sessionId(), matches, hit.lastActivityAt());
        }).filter(r -> r.matches() > 0).sorted(RANKING).limit(limit).map(Ranked::sessionId).toList();
        return new Recommendations(List.copyOf(unique.values()), reader.cards(ranked, actor));
    }
    public static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) throw new CustomException(ErrorCode.INVALID_LIMIT_REQUEST);
    }
    private record Ranked(Long sessionId, long matches, LocalDateTime lastActivityAt) { }
    public record Recommendations(List<String> basisHashtags, List<CommunityCard> mindmaps) { }
}
