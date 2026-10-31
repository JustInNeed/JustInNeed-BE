package com.justinneed.community.reaction;

import com.justinneed.community.bookmark.TargetType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ExplorationRepository extends JpaRepository<Exploration, Long> {
    Optional<Exploration> findByActorIdAndTargetTypeAndTargetId(Long actorId, TargetType type, Long targetId);
    // 탈퇴 회원의 행은 남기고 숫자에서만 뺀다
    @Query("""
            select count(e) from Exploration e where e.targetType = :type and e.targetId = :targetId
            and exists (select m.id from Member m where m.id = e.actorId and m.withdrawnAt is null)
            """)
    long countByTargetTypeAndTargetId(@Param("type") TargetType type, @Param("targetId") Long targetId);
}
