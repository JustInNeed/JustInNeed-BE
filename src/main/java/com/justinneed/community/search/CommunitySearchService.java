package com.justinneed.community.search;

import com.justinneed.community.bookmark.BookmarkQueryService;
import com.justinneed.community.catalog.CommunityTags;
import com.justinneed.global.common.HashtagValidator;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CommunitySearchService {
    private final PublicDirectory directory;
    public CommunitySearchService(PublicDirectory directory) { this.directory = directory; }

    public Profiles profiles(String hashtag, int page, int size) {
        var found = find(hashtag, page, size, false);
        return new Profiles(200, found.owners().stream().map(o -> new ProfileItem(o.ownerId(), o.nickname(),
                matched(o, found.key(), false), o.nodeCount())).toList(), found.hasNext());
    }
    public Mindmaps mindmaps(String hashtag, int page, int size) {
        var found = find(hashtag, page, size, true);
        return new Mindmaps(200, found.owners().stream().map(o -> new MindmapItem(o.ownerId(), o.ownerId(),
                o.nickname(), "Community mindmap", matched(o, found.key(), true), o.nodeCount())).toList(), found.hasNext());
    }
    private Found find(String hashtag, int page, int size, boolean mindmap) {
        String key = CommunityTags.key(HashtagValidator.normalizeAndValidate(List.of(hashtag)).get(0));
        long offset = BookmarkQueryService.paging(page, size).getOffset();
        var result = new ArrayList<PublicDirectory.PublicOwner>();
        long[] skipped = {0};
        directory.visit(owner -> {
            if (matched(owner, key, mindmap).isEmpty()) return true;
            if (skipped[0]++ < offset) return true;
            result.add(owner);
            return result.size() <= size;
        });
        boolean more = result.size() > size;
        return new Found(key, result.stream().limit(size).toList(), more);
    }
    // 프로필 검색은 관심사도 본다. 마인드맵 검색은 노드가 있는 카드의 태그만 본다
    private List<String> matched(PublicDirectory.PublicOwner owner, String key, boolean requireLeaves) {
        var names = owner.cardTags(Set.of(key));
        if (!names.isEmpty() || requireLeaves) return names;
        return owner.interests().stream().filter(t -> CommunityTags.key(t).equals(key)).limit(1).toList();
    }
    private record Found(String key, List<PublicDirectory.PublicOwner> owners, boolean hasNext) { }
    public record ProfileItem(Long userId, String nickname, List<String> matchedHashtags, long totalNodeCount) { }
    public record MindmapItem(Long mindmapId, Long ownerId, String nickname, String title, List<String> hashtags, long nodeCount) { }
    public record Profiles(int code, List<ProfileItem> profiles, boolean hasNext) { }
    public record Mindmaps(int code, List<MindmapItem> mindmaps, boolean hasNext) { }
}
