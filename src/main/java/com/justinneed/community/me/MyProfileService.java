package com.justinneed.community.me;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.bookmark.BookmarkSnapshot;
import com.justinneed.community.card.CardCounts;
import com.justinneed.community.card.CardSessionsService;
import com.justinneed.community.card.CardSnippet;
import com.justinneed.community.card.CommunityCard.Owner;
import com.justinneed.community.card.CommunityCard.RelatedUrl;
import com.justinneed.community.card.CommunityCardSync;
import com.justinneed.community.card.SessionMindmaps;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.domain.CommunityProfile;
import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.community.edit.ProfileEditService;
import com.justinneed.community.edit.ProfileEditService.TagView;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.community.reaction.ProfileLikeRepository;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.community.service.CommunityProfiles;
import com.justinneed.community.service.CommunitySharing;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.home.service.HomeService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.dto.SessionListResponse;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 마이프로필. 공개 프로필 API(PublicGraphService · CommunityCardReader · CardSessionsService)는 작성자 본인에게도 방문자 규칙이고,
 * 여기는 본인 전용이라 공개 여부와 상관없이 내 완료 · 미삭제 세션 전체를 보여주고 세션마다 공개 여부를 표시한다.
 *
 * <pre>
 * 고정   = 공개 세션 중 맨 위에 두는 것. 핀에 남아 있어도 지금 공개 · 완료 · 미삭제가 아니면 고정으로 보지 않는다
 * 순서   = 고정 세션(핀 순서) → 최근 활동(종료 또는 시작 시각) 최신순 → id 역순
 * </pre>
 */
@Service
@Transactional(readOnly = true)
public class MyProfileService {
    private static final int MAX_TOP_HASHTAGS = 5;

    private final CommunityProfiles profiles;
    private final CommunitySharing sharing;
    private final CommunitySessionRepository sessions;
    private final CommunityTags tags;
    private final SessionMindmaps mindmaps;
    private final CardCounts counts;
    private final ProfileEditService edit;
    private final HomeService home;
    private final ProfileLikeRepository likes;
    private final MemberRepository members;

    public MyProfileService(CommunityProfiles profiles, CommunitySharing sharing, CommunitySessionRepository sessions,
            CommunityTags tags, SessionMindmaps mindmaps, CardCounts counts, ProfileEditService edit, HomeService home,
            ProfileLikeRepository likes, MemberRepository members) {
        this.profiles = profiles; this.sharing = sharing; this.sessions = sessions; this.tags = tags;
        this.mindmaps = mindmaps; this.counts = counts; this.edit = edit; this.home = home;
        this.likes = likes; this.members = members;
    }

    public MyProfile profile(Long userId) {
        var profile = profiles.read(userId);
        var hashtags = edit.hashtags(userId);
        var top = hashtags.stream().filter(tag -> tag.nodeCount() > 0)
                .sorted(Comparator.comparingLong(TagView::nodeCount).reversed()
                        .thenComparing(tag -> CommunityTags.key(tag.hashtag())).thenComparing(TagView::hashtagId))
                .limit(MAX_TOP_HASHTAGS).toList();
        var interests = edit.myInterests(userId).stream()
                .map(interest -> new MyInterest(interest.interestId(), interest.hashtag())).toList();
        var mine = ordered(profile);
        var pinned = mine.sessions().stream().filter(session -> mine.isPinned(session))
                .map(session -> MySession.of(session, true)).toList();
        return new MyProfile(userId, nickname(userId), profile.getBio(), profile.getVisibility(), home.totalNodes(userId),
                sharing.visibleSessions(profile).size(), likes.countByProfileId(userId), top, hashtags, interests, pinned);
    }

    public MySessions sessions(Long userId, int page, int size, boolean publicOnly) {
        var mine = ordered(profiles.read(userId));
        var shown = mine.sessions().stream().filter(session -> !publicOnly || session.isPublicSession()).toList();
        var found = page(shown, page, size);
        return new MySessions(found.items().stream().map(session -> MySession.of(session, mine.isPinned(session))).toList(),
                found.hasNext());
    }

    public MyCards mindmaps(Long userId, int page, int size) {
        var mine = ordered(profiles.read(userId));
        var found = page(mine.sessions(), page, size);
        var maps = mindmaps.of(found.items());
        var numbers = counts.counts(found.items().stream().map(BrowsingSession::getId).toList(), userId);
        var owner = new Owner(userId, nickname(userId));
        return new MyCards(found.items().stream().map(session -> MyCard.of(session, owner, maps.get(session.getId()).nodes().size(),
                numbers.getOrDefault(session.getId(), CardCounts.Count.ZERO), mine.isPinned(session))).toList(), found.hasNext());
    }

    /** 그 세션 + 태그가 겹치는 내 다른 세션. 공개 세션 · 공개 태그로 좁히지 않는다. */
    public CardSessionsService.Sessions related(Long userId, Long sessionId) {
        profiles.read(userId);
        var mine = sessions.findCompletedSessions(userId);
        var anchor = mine.stream().filter(session -> session.getId().equals(sessionId)).findFirst()
                .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));
        var anchorTags = tagKeys(anchor);
        var others = mine.stream().filter(session -> !session.getId().equals(sessionId))
                .filter(session -> !Collections.disjoint(anchorTags, tagKeys(session)))
                .sorted(CardSessionsService.NEWEST_SESSION_FIRST)
                .limit(CardSessionsService.MAX_SESSIONS - 1);
        return new CardSessionsService.Sessions(Stream.concat(Stream.of(anchor), others).map(SessionListResponse::from).toList());
    }

    /** 공개 그래프와 같은 모양의 내 전체 마인드맵. 행이 없는 옛 태그와 노드가 없는 세션의 태그는 빠진다. */
    public PublicGraphService.Graph mindmap(Long userId) {
        profiles.read(userId);
        String nickname = nickname(userId);
        var tagIndex = tags.index(userId);
        var mine = sessions.findCompletedSessions(userId);
        var maps = mindmaps.of(mine);
        var hashtags = new LinkedHashMap<Long, BookmarkSnapshot.Tag>();
        var nodes = new ArrayList<BookmarkSnapshot.Node>();
        var edges = new LinkedHashSet<BookmarkSnapshot.Edge>();
        // 비공개 세션은 community_nodes 행이 없어 nodeId 는 이 응답 안에서만 쓰는 번호다. 북마크에는 쓰지 않는다
        long[] sequence = {0};
        for (var session : mine) {
            var part = PublicGraphService.aiPart(maps.get(session.getId()), aiId -> ++sequence[0]);
            if (part.nodes().isEmpty()) continue;
            nodes.addAll(part.nodes());
            edges.addAll(part.edges());
            tagKeys(session).stream().map(tagIndex::get).filter(Objects::nonNull).forEach(tag -> {
                hashtags.putIfAbsent(tag.getId(), new BookmarkSnapshot.Tag(tag.getId(), userId, tag.getName()));
                part.entries().forEach(node -> edges.add(new BookmarkSnapshot.Edge("tag:" + tag.getId(), "node:" + node)));
            });
        }
        return new PublicGraphService.Graph(200, userId, userId, nickname, PublicGraphService.graphTitle(nickname),
                List.copyOf(hashtags.values()), List.copyOf(nodes), List.copyOf(edges), List.of());
    }

    private Ordered ordered(CommunityProfile profile) {
        var mine = sessions.findCompletedSessions(profile.getUserId());
        Map<Long, BrowsingSession> open = mine.stream().filter(BrowsingSession::isPublicSession)
                .collect(Collectors.toMap(BrowsingSession::getId, Function.identity()));
        var pins = profile.getPinnedSessionIds().stream().distinct().filter(open::containsKey).map(open::get).toList();
        var pinned = pins.stream().map(BrowsingSession::getId).collect(Collectors.toSet());
        var rest = mine.stream().filter(session -> !pinned.contains(session.getId()))
                .sorted(CardSessionsService.NEWEST_SESSION_FIRST);
        return new Ordered(Stream.concat(pins.stream(), rest).toList(), pinned);
    }

    private static <T> Page<T> page(List<T> items, int page, int size) {
        long offset = BookmarkQueryService.paging(page, size).getOffset();
        return new Page<>(items.stream().skip(offset).limit(size).toList(), items.size() > offset + size);
    }

    private String nickname(Long userId) {
        return members.findByIdAndWithdrawnAtIsNull(userId).map(Member::getNickname).orElseThrow(CommunityErrors::missing);
    }

    private static Set<String> tagKeys(BrowsingSession session) {
        return session.getTags().stream().map(CommunityTags::key).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private record Ordered(List<BrowsingSession> sessions, Set<Long> pinned) {
        boolean isPinned(BrowsingSession session) { return pinned.contains(session.getId()); }
    }
    private record Page<T>(List<T> items, boolean hasNext) { }

    public record MyInterest(Long interestId, String hashtag) { }
    public record MyProfile(Long userId, String nickname, String bio, NodeVisibility visibility, long totalNodeCount,
            long sharedSessionCount, long likeCount, List<TagView> topHashtags, List<TagView> hashtags,
            List<MyInterest> interests, List<MySession> pinnedSessions) { }
    public record MySessions(List<MySession> sessions, boolean hasNext) { }
    public record MyCards(List<MyCard> mindmaps, boolean hasNext) { }

    /** SessionListResponse + isPinned. */
    public record MySession(Long id, String title, LocalDateTime startedAt, LocalDateTime endedAt, int pageCount,
            SessionStatus status, boolean isFavorite, boolean isPublic, List<String> tags, boolean isPinned) {
        static MySession of(BrowsingSession session, boolean pinned) {
            return new MySession(session.getId(), session.getTitle(), session.getStartedAt(), session.getEndedAt(),
                    session.getPageCount(), session.getStatus(), session.isFavorite(), session.isPublicSession(),
                    session.getTags(), pinned);
        }
    }

    /** CommunityCard + isPublic · isPinned. 본인에게는 공개 범위와 상관없이 제목 · 요약 · 전체 태그가 보이고 nodeCount 는 AI 노드 수다. */
    public record MyCard(Long mindmapId, Owner owner, String title, String snippet, List<String> hashtags, long nodeCount,
            long likeCount, boolean likedByMe, long bookmarkCount, Long bookmarkId, long exploringCount,
            boolean exploringByMe, LocalDateTime lastActivityAt, List<RelatedUrl> relatedUrls,
            boolean isPublic, boolean isPinned) {
        static MyCard of(BrowsingSession session, Owner owner, long nodeCount, CardCounts.Count count, boolean pinned) {
            var summary = session.getSummary();
            return new MyCard(session.getId(), owner, session.getTitle(), summary == null ? null : CardSnippet.of(summary.getMarkdown()),
                    session.getTags(), nodeCount, count.likeCount(), count.likedByMe(), count.bookmarkCount(), count.bookmarkId(),
                    count.exploringCount(), count.exploringByMe(), CardSessionsService.lastActivityAt(session),
                    CommunityCardSync.relatedUrls(session), session.isPublicSession(), pinned);
        }
    }
}
