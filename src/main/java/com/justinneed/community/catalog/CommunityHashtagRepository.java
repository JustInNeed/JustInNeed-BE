package com.justinneed.community.catalog;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityHashtagRepository extends JpaRepository<CommunityHashtag, Long> {
    List<CommunityHashtag> findByOwnerIdOrderByIdAsc(Long ownerId);

    @Query("select h from CommunityHashtag h where h.id in :ids"
            + " and exists (select m.id from Member m where m.id = h.ownerId and m.withdrawnAt is null)")
    List<CommunityHashtag> findWithOwnerByIdIn(@Param("ids") Collection<Long> ids);
}
