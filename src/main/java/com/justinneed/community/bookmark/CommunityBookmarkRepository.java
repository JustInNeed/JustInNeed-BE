package com.justinneed.community.bookmark;

import com.justinneed.community.card.CardCounts;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityBookmarkRepository extends JpaRepository<CommunityBookmark, Long> {
    Slice<CommunityBookmark> findByUserIdAndSaveType(Long userId, SaveType saveType, Pageable pageable);
    Optional<CommunityBookmark> findByIdAndUserId(Long id, Long userId);
    Optional<CommunityBookmark> findByUserIdAndSelectionKey(Long userId, String selectionKey);
    Slice<CommunityBookmark> findByUserId(Long userId, Pageable pageable);

    // 탈퇴 회원의 행은 남기고 숫자에서만 뺀다
    @Query("""
            select b.cardSessionId as sessionId, count(b) as total from CommunityBookmark b
            where b.cardSessionId in :sessionIds
            and exists (select m.id from Member m where m.id = b.userId and m.withdrawnAt is null)
            group by b.cardSessionId
            """)
    List<CardCounts.Total> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    // 스냅샷 JSON 을 읽지 않도록 id 만 가져온다
    @Query("""
            select b.cardSessionId as sessionId, b.id as bookmarkId from CommunityBookmark b
            where b.userId = :userId and b.cardSessionId in :sessionIds
            """)
    List<CardBookmark> findCardBookmarks(@Param("userId") Long userId, @Param("sessionIds") Collection<Long> sessionIds);

    @Modifying
    @Query("delete from CommunityBookmark b where b.userId = :userId and b.cardSessionId = :sessionId")
    int deleteCard(@Param("userId") Long userId, @Param("sessionId") Long sessionId);

    interface CardBookmark {
        Long getSessionId();

        Long getBookmarkId();
    }
}
