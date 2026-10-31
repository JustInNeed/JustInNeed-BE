package com.justinneed.community.reaction;

import com.justinneed.community.card.CardCounts;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface MindmapExplorationRepository extends Repository<MindmapExploration, Long> {
    // 새로 넣었을 때만 1 이다. 이미 눌렀으면 활동을 다시 남기지 않는다
    @Modifying
    @Query(value = "insert into community_mindmap_explorations (actor_id, session_id, created_at)"
            + " values (:actorId, :sessionId, :createdAt) on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("actorId") Long actorId, @Param("sessionId") Long sessionId,
            @Param("createdAt") LocalDateTime createdAt);

    @Modifying
    @Query("delete from MindmapExploration e where e.actorId = :actorId and e.sessionId = :sessionId")
    int deletePair(@Param("actorId") Long actorId, @Param("sessionId") Long sessionId);

    // 탈퇴 회원의 행은 남기고 숫자에서만 뺀다
    @Query("""
            select e.sessionId as sessionId, count(e) as total from MindmapExploration e where e.sessionId in :sessionIds
            and exists (select m.id from Member m where m.id = e.actorId and m.withdrawnAt is null)
            group by e.sessionId
            """)
    List<CardCounts.Total> countBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);

    @Query("select e.sessionId from MindmapExploration e where e.actorId = :actorId and e.sessionId in :sessionIds")
    List<Long> findExploringSessions(@Param("actorId") Long actorId, @Param("sessionIds") Collection<Long> sessionIds);
}
