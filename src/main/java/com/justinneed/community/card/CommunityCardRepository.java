package com.justinneed.community.card;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommunityCardRepository extends JpaRepository<CommunityCardRow, Long> {

    List<CommunityCardRow> findByOwnerIdInOrderBySessionIdDesc(Collection<Long> ownerIds);

    @Modifying
    @Query("delete from CommunityCardRow card where card.ownerId = :ownerId")
    void deleteAllOfOwner(@Param("ownerId") Long ownerId);
}
