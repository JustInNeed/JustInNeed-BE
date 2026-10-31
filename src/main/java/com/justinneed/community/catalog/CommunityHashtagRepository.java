package com.justinneed.community.catalog;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityHashtagRepository extends JpaRepository<CommunityHashtag, Long> {
    List<CommunityHashtag> findByOwnerIdOrderByIdAsc(Long ownerId);
    List<CommunityHashtag> findByOwnerIdInOrderByIdAsc(Collection<Long> ownerIds);

    @Query("select h from CommunityHashtag h where h.id in :ids"
            + " and exists (select m.id from Member m where m.id = h.ownerId and m.withdrawnAt is null)")
    List<CommunityHashtag> findWithOwnerByIdIn(@Param("ids") Collection<Long> ids);

    // 같은 작성자의 세션 둘이 동시에 저장돼도 유니크 위반 없이 한쪽만 들어간다
    @Modifying
    @Query(value = "insert into community_hashtags (owner_id, name, normalized_name)"
            + " values (:ownerId, :name, :normalizedName) on conflict do nothing", nativeQuery = true)
    void insertIfAbsent(@Param("ownerId") Long ownerId, @Param("name") String name,
            @Param("normalizedName") String normalizedName);
}
