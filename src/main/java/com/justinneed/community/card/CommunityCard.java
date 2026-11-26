package com.justinneed.community.card;

import java.time.LocalDateTime;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * 커뮤니티 카드 응답. 공개 세션 1개 = 카드 1장, mindmapId = sessions.id. URL 만 공개한 작성자의 카드는 title · snippet ·
 * relatedUrls[].title 이 null 이다 (FE 가 제목 자리에 해시태그를 보여준다).
 */
public record CommunityCard(
        Long mindmapId,
        Owner owner,
        @Nullable String title,
        @Nullable String snippet,
        List<String> hashtags,
        long nodeCount,
        long likeCount,
        boolean likedByMe,
        long bookmarkCount,
        @Nullable Long bookmarkId,
        LocalDateTime lastActivityAt,
        List<RelatedUrl> relatedUrls) {

    public record Owner(Long userId, @Nullable String nickname) {}

    public record RelatedUrl(@Nullable String title, String url) {}
}
