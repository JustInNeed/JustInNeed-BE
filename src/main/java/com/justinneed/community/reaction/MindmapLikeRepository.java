package com.justinneed.community.reaction;

import com.justinneed.community.card.CardCounts;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MindmapLikeRepository extends Repository<MindmapLike, Long> {
    // 같은 카드를 동시에 눌러도 유니크 제약 오류 없이 한 행만 남는다
    @Modifying
    @Query(value = "insert into community_mindmap_likes (actor_id, session_id, created_at)"
            + " values (:actorId, :sessionId, :createdAt) on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("actorId") Long actorId, @Param("sessionId") Long sessionId,
            @Param("createdAt") LocalDateTime createdAt);

    // 벌크 삭제라 동시 취소가 이미 지운 행을 다시 지우려다 실패하지 않는다
    @Modifying
    @Query("delete from MindmapLike l where l.actorId = :actorId and l.sessionId = :sessionId")
    int deletePair(@Param("actorId") Long actorId, @Param("sessionId") Long sessionId);

    // 탈퇴 회원의 행은 남기고 숫자에서만 뺀다
    @Query("""
            select l.sessionId as sessionId, count(l) as total from MindmapLike l where l.sessionId in :sessionIds
            and exists (select m.id from Member m where m.id = l.actorId and m.withdrawnAt is null)
            group by l.sessionId
            """)
    List<CardCounts.Total> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Query("select l.sessionId from MindmapLike l where l.actorId = :actorId and l.sessionId in :sessionIds")
    List<Long> findLikedSessions(@Param("actorId") Long actorId, @Param("sessionIds") Collection<Long> sessionIds);
}
