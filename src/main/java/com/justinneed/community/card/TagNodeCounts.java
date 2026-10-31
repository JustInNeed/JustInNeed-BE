package com.justinneed.community.card;

import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.repository.CommunitySessionRepository;
import com.justinneed.home.HomeGraphBuilder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 내 해시태그별 노드 수: 그 태그가 달린 완료 · 미삭제 세션의 AI 노드를 홈과 같은 키로 합친 개수. 공개 여부는 보지 않는다. */
@Component
public class TagNodeCounts {
    private final CommunitySessionRepository sessions;
    private final SessionMindmaps mindmaps;

    public TagNodeCounts(CommunitySessionRepository sessions, SessionMindmaps mindmaps) {
        this.sessions = sessions;
        this.mindmaps = mindmaps;
    }

    /** 소문자 태그 이름(CommunityTags.key) → 노드 수. 노드가 없는 태그는 빠진다. */
    public Map<String, Long> byTag(Long ownerId) {
        var mine = sessions.findCompletedSessions(ownerId);
        var maps = mindmaps.of(mine);
        Map<String, Set<String>> keywords = new HashMap<>();
        for (var session : mine) {
            List<String> keys = maps.get(session.getId()).nodes().stream()
                    .map(node -> HomeGraphBuilder.keyOf(node.keyword())).filter(key -> !key.isEmpty()).toList();
            if (keys.isEmpty()) continue;
            session.getTags().forEach(tag ->
                    keywords.computeIfAbsent(CommunityTags.key(tag), key -> new HashSet<>()).addAll(keys));
        }
        Map<String, Long> counts = new HashMap<>();
        keywords.forEach((tag, keys) -> counts.put(tag, (long) keys.size()));
        return counts;
    }
}
