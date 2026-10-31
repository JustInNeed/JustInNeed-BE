package com.justinneed.community.recommend;

import com.justinneed.community.api.CommunityErrors;
import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.community.search.PublicDirectory;
import com.justinneed.community.service.CommunityProfiles;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class RecommendationService {
    private final CommunityProfiles profiles;
    private final BookmarkQueryService bookmarks;
    private final PublicDirectory directory;
    public RecommendationService(CommunityProfiles profiles, BookmarkQueryService bookmarks, PublicDirectory directory) {
        this.profiles = profiles; this.bookmarks = bookmarks; this.directory = directory;
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
        basis.forEach(t -> unique.putIfAbsent(CommunityTags.key(t), t));
        if (unique.isEmpty()) return new Recommendations(200, List.of(), List.of());
        var found = new ArrayList<Mindmap>();
        var ranking = Comparator.<Mindmap>comparingInt(m -> m.matchedHashtags().size()).reversed()
                .thenComparing(Mindmap::ownerId);
        directory.visit(owner -> {
            if (owner.ownerId().equals(actor)) return true;
            var tags = owner.cardTags(unique.keySet());
            if (tags.isEmpty()) return true;
            var urls = owner.cards().stream().filter(c -> c.nodeCount() > 0)
                    .filter(c -> c.hashtags().stream().anyMatch(t -> unique.containsKey(CommunityTags.key(t))))
                    .flatMap(c -> c.urls().stream()).distinct().limit(10).toList();
            found.add(new Mindmap(owner.ownerId(), "Community mindmap", owner.ownerId(), tags, urls));
            found.sort(ranking);
            if (found.size() > limit) found.remove(found.size() - 1);
            return true;
        });
        return new Recommendations(200, List.copyOf(unique.values()), List.copyOf(found));
    }
    public static void checkLimit(int limit) {
        if (limit < 1 || limit > 100) throw CommunityErrors.invalid("limit must be between 1 and 100");
    }
    public record Mindmap(Long mindmapId, String title, Long ownerId, List<String> matchedHashtags, List<String> relatedUrls) { }
    public record Recommendations(int code, List<String> basisHashtags, List<Mindmap> mindmaps) { }
}
