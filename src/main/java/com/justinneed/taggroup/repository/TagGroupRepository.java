package com.justinneed.taggroup.repository;

import com.justinneed.taggroup.domain.TagGroup;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagGroupRepository extends JpaRepository<TagGroup, Long> {

    List<TagGroup> findByUserIdOrderByPositionAscIdAsc(Long userId);

    Optional<TagGroup> findByIdAndUserId(Long id, Long userId);

    int countByUserId(Long userId);
}
