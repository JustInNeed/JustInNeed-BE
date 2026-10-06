package com.justinneed.community.search;

import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.global.common.HashtagValidator;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CommunitySearchService {
    private final PublicDirectory directory;
    public CommunitySearchService(PublicDirectory directory) { this.directory = directory; }

    public Profiles profiles(String hashtag, int page, int size) {
        var found = find(hashtag, page, size, false);
        return new Profiles(200, found.graphs().stream().map(g -> new ProfileItem(g.ownerId(), g.nickname(),
                matched(g, found.key(), false), g.nodes().size())).toList(), found.hasNext());
    }
    public Mindmaps mindmaps(String hashtag, int page, int size) {
        var found = find(hashtag, page, size, true);
        return new Mindmaps(200, found.graphs().stream().map(g -> new MindmapItem(g.mindmapId(), g.ownerId(),
                g.nickname(), g.title(), matched(g, found.key(), true), g.nodes().size())).toList(), found.hasNext());
    }
    private Found find(String hashtag, int page, int size, boolean mindmap) {
        String key = CommunityTags.key(HashtagValidator.normalizeAndValidate(List.of(hashtag)).get(0));
        long offset = BookmarkQueryService.paging(page, size).getOffset();
        var result = new ArrayList<PublicGraphService.Graph>();
        long[] skipped = {0};
        directory.visit(graph -> {
            if (matched(graph, key, mindmap).isEmpty()) return true;
            if (skipped[0]++ < offset) return true;
            result.add(graph);
            return result.size() <= size;
        });
        boolean more = result.size() > size;
        return new Found(key, result.stream().limit(size).toList(), more);
    }
    private List<String> matched(PublicGraphService.Graph graph, String key, boolean requireLeaves) {
        return graph.hashtags().stream()
                .filter(t -> CommunityTags.key(t.name()).equals(key))
                .filter(t -> !requireLeaves || graph.edges().stream().anyMatch(e -> e.from().equals("tag:" + t.hashtagId())))
                .map(t -> t.name()).toList();
    }
    private record Found(String key, List<PublicGraphService.Graph> graphs, boolean hasNext) { }
    public record ProfileItem(Long userId, String nickname, List<String> matchedHashtags, long totalNodeCount) { }
    public record MindmapItem(Long mindmapId, Long ownerId, String nickname, String title, List<String> hashtags, long nodeCount) { }
    public record Profiles(int code, List<ProfileItem> profiles, boolean hasNext) { }
    public record Mindmaps(int code, List<MindmapItem> mindmaps, boolean hasNext) { }
}
