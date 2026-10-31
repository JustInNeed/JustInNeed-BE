package com.justinneed.community.bookmark;

import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityBookmarkRepository extends JpaRepository<CommunityBookmark, Long> {
    Slice<CommunityBookmark> findByUserIdAndSaveType(Long userId, SaveType saveType, Pageable pageable);
    Optional<CommunityBookmark> findByIdAndUserId(Long id, Long userId);
    Optional<CommunityBookmark> findByUserIdAndSelectionKey(Long userId, String selectionKey);
    Slice<CommunityBookmark> findByUserId(Long userId, Pageable pageable);
}
