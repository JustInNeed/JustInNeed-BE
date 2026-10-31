package com.justinneed.community.card;

import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.catalog.CommunityHashtag;
import com.justinneed.community.catalog.CommunityHashtagRepository;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 조회 때 만들던 행(community_hashtags · community_nodes)과 community_cards 를 쓰기 시점으로 옮기기 전에 생긴 세션을
 * 앱 시작 때 채운다. 빠진 행만 만들므로 한 번 돈 뒤에는 읽기만 하고 끝나, 매번 시작할 때 실행해도 안전하다.
 * 한 세션이 실패해도 앱 시작을 막지 않고 나머지를 진행한다 (다음 시작 때 다시 시도).
 */
@Component
public class CommunityCardBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommunityCardBackfill.class);

    private final CommunitySessionRepository communitySessions;
    private final BrowsingSessionRepository sessions;
    private final CommunityHashtagRepository hashtags;
    private final CommunityTags tags;
    private final CommunityCardSync sync;
    private final TransactionTemplate transaction;

    public CommunityCardBackfill(CommunitySessionRepository communitySessions, BrowsingSessionRepository sessions,
            CommunityHashtagRepository hashtags, CommunityTags tags, CommunityCardSync sync,
            PlatformTransactionManager transactionManager) {
        this.communitySessions = communitySessions;
        this.sessions = sessions;
        this.hashtags = hashtags;
        this.tags = tags;
        this.sync = sync;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        int tagCount = fillTags();
        int cardCount = fillCards();
        if (tagCount > 0 || cardCount > 0) {
            log.info("[community card backfill] 해시태그 {}건 · 카드 {}건 채움", tagCount, cardCount);
        }
    }

    private int fillTags() {
        Map<Long, List<String>> wanted = new HashMap<>();
        communitySessions.findLiveSessionTags().forEach(row ->
                wanted.computeIfAbsent(row.userId(), ignored -> new ArrayList<>()).addAll(row.tags()));
        communitySessions.findLiveProfiles().forEach(profile -> {
            var names = wanted.computeIfAbsent(profile.getUserId(), ignored -> new ArrayList<>());
            names.addAll(profile.getInterests());
            names.addAll(profile.getHiddenHashtags());
        });

        Map<Long, Set<String>> existing = new HashMap<>();
        for (CommunityHashtag tag : hashtags.findAll()) {
            existing.computeIfAbsent(tag.getOwnerId(), ignored -> new HashSet<>()).add(tag.getNormalizedName());
        }
        int created = 0;
        for (var entry : wanted.entrySet()) {
            Set<String> have = existing.getOrDefault(entry.getKey(), Set.of());
            List<String> missing = entry.getValue().stream().filter(name -> !have.contains(CommunityTags.key(name))).toList();
            if (!missing.isEmpty()) {
                tags.ensure(entry.getKey(), missing);
                created += missing.size();
            }
        }
        return created;
    }

    private int fillCards() {
        int filled = 0;
        for (Long id : communitySessions.findPublicIdsWithoutCard()) {
            try {
                // 세션을 고치는 다른 경로와 같이 세션 행을 먼저 잠근다
                transaction.executeWithoutResult(status -> sessions.lockById(id).ifPresent(sync::sync));
                filled++;
            } catch (RuntimeException error) {
                log.warn("[community card backfill] session {} 카드를 만들지 못함: {}", id, error.getMessage());
            }
        }
        return filled;
    }
}
