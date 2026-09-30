package com.justinneed.community.catalog;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityHashtagRepository extends JpaRepository<CommunityHashtag, Long> {
    List<CommunityHashtag> findByOwnerIdOrderByIdAsc(Long ownerId);
}
