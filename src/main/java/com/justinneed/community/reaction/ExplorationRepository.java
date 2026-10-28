package com.justinneed.community.reaction;

import com.justinneed.community.bookmark.TargetType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExplorationRepository extends JpaRepository<Exploration, Long> {
    Optional<Exploration> findByActorIdAndTargetTypeAndTargetId(Long actorId, TargetType type, Long targetId);
    long countByTargetTypeAndTargetId(TargetType type, Long targetId);
}
