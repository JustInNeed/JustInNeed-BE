package com.justinneed.community.repository;

import com.justinneed.auth.domain.Member;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Withdrawn members are treated as missing everywhere in community.
public interface CommunityMemberRepository extends Repository<Member, Long> {
    boolean existsByIdAndWithdrawnAtIsNull(Long id);

    @Query("select m from Member m where m.id in :ids and m.withdrawnAt is null")
    List<Member> findActiveByIdIn(@Param("ids") Collection<Long> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id and m.withdrawnAt is null")
    Optional<Member> lockById(@Param("id") Long id);
}
