package com.justinneed.community.card;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.community.api.HttpUrl;
import com.justinneed.community.api.SelectionKey;
import com.justinneed.community.card.CommunityCard.RelatedUrl;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.graph.CommunityNode;
import com.justinneed.community.graph.CommunityNodeRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.dto.SessionMindmapResponse;
import com.justinneed.session.summary.domain.Summary;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 세션이 바뀔 때 커뮤니티가 읽을 행을 미리 맞춘다. 세션을 잠근 쓰기 트랜잭션 안에서 부른다. 커뮤니티 GET 은 이 행들을 읽기만 한다.
 *
 * <pre>
 * community_hashtags = 지우지 않은 세션의 태그 (프로필 수정의 해시태그 목록이 비공개 세션의 태그도 보여준다)
 * community_nodes    = 공개 카드 세션의 URL 노드 + AI 노드. 공개 범위가 바뀌어도 다시 쓰지 않도록 둘 다 만든다
 *                      북마크가 id 를 들고 있어 지우지 않는다
 * community_cards    = 공개 카드 세션만. 그 밖의 세션은 행을 지운다
 * </pre>
 *
 * 부르는 곳: 세션 수정 · 삭제, 공유 핀 · 해제, AI 결과 저장, 재분석.
 * 공개 범위 · 비공개 해시태그는 읽을 때 적용하므로 바꿔도 부르지 않는다.
 */
@Service
public class CommunityCardSync {
    private static final int MAX_URLS = 10;

    private final CommunityCardRepository cards;
    private final CommunityTags tags;
    private final CommunityNodeRepository nodes;
    private final SessionMindmaps mindmaps;
    private final MemberRepository members;

    public CommunityCardSync(CommunityCardRepository cards, CommunityTags tags, CommunityNodeRepository nodes,
            SessionMindmaps mindmaps, MemberRepository members) {
        this.cards = cards;
        this.tags = tags;
        this.nodes = nodes;
        this.mindmaps = mindmaps;
        this.members = members;
    }

    @Transactional
    public void sync(BrowsingSession session) {
        if (!session.isDeleted()) {
            tags.ensure(session.getUserId(), session.getTags());
        }
        if (!isPublicCard(session)) {
            cards.findById(session.getId()).ifPresent(cards::delete);
            return;
        }
        Map<String, Source> urls = uniqueUrls(session);
        SessionMindmapResponse mindmap = mindmaps.of(session);
        ensureNodes(session, urls.keySet(), mindmap);

        CommunityCardRow card = cards.findById(session.getId())
                .orElseGet(() -> new CommunityCardRow(session.getId(), session.getUserId()));
        Summary summary = session.getSummary();
        card.replace(session.getTitle(), summary == null ? null : CardSnippet.of(summary.getMarkdown()),
                session.getTags(), relatedUrls(urls), mindmap.nodes().size(), urls.size(),
                session.getEndedAt() != null ? session.getEndedAt() : session.getStartedAt());
        cards.save(card);
    }

    // 탈퇴하면 세션 행은 남지만 카드에서는 빠진다
    @Transactional
    public void removeOwner(Long ownerId) {
        cards.deleteAllOfOwner(ownerId);
    }

    private boolean isPublicCard(BrowsingSession session) {
        return session.isPublicSession() && !session.isDeleted() && session.getStatus() == SessionStatus.COMPLETED
                && !members.findById(session.getUserId()).map(Member::isWithdrawn).orElse(true);
    }

    /** 카드의 relatedUrls: 세션의 http(s) URL 을 중복 없이 앞에서 10개. */
    public static List<RelatedUrl> relatedUrls(BrowsingSession session) {
        return relatedUrls(uniqueUrls(session));
    }

    private static Map<String, Source> uniqueUrls(BrowsingSession session) {
        Map<String, Source> urls = new LinkedHashMap<>();
        for (Source source : session.getSources()) {
            if (HttpUrl.isValid(source.url())) {
                urls.putIfAbsent(source.url(), source);
            }
        }
        return urls;
    }

    private static List<RelatedUrl> relatedUrls(Map<String, Source> urls) {
        return urls.values().stream().limit(MAX_URLS)
                .map(source -> new RelatedUrl(source.title() == null || source.title().isBlank() ? null : source.title(),
                        source.url()))
                .toList();
    }

    private void ensureNodes(BrowsingSession session, Set<String> urls, SessionMindmapResponse mindmap) {
        List<String> keys = new ArrayList<>();
        urls.forEach(url -> keys.add(SelectionKey.of(url)));
        mindmap.nodes().forEach(node -> keys.add(SelectionKey.of("ai:" + node.id())));

        Set<String> existing = new HashSet<>();
        nodes.findBySessionId(session.getId()).forEach(node -> existing.add(node.getSourceKey()));
        keys.stream().filter(existing::add)
                .forEach(key -> nodes.save(new CommunityNode(session.getUserId(), session.getId(), key)));
    }
}
