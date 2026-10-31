package com.justinneed.community.follow;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CommunityFollowRepository extends Repository<CommunityFollow, Long> {
    // 같은 팔로우를 동시에 눌러도 유니크 제약 오류 없이 한 행만 남는다
    @Modifying
    @Query(value = "insert into community_follows (actor_id, target_id, created_at)"
            + " values (:actorId, :targetId, :createdAt) on conflict do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("actorId") Long actorId, @Param("targetId") Long targetId,
            @Param("createdAt") LocalDateTime createdAt);

    // 벌크 삭제라 동시 취소가 이미 지운 행을 다시 지우려다 실패하지 않는다
    @Modifying
    @Query("delete from CommunityFollow f where f.actorId = :actorId and f.targetId = :targetId")
    int deletePair(@Param("actorId") Long actorId, @Param("targetId") Long targetId);

    boolean existsByActorIdAndTargetId(Long actorId, Long targetId);

    @Query("select f.targetId from CommunityFollow f where f.actorId = :actorId and f.targetId in :targetIds")
    List<Long> findFollowedTargets(@Param("actorId") Long actorId, @Param("targetIds") Collection<Long> targetIds);
}
