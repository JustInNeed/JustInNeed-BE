package com.justinneed.community.repository;

import com.justinneed.auth.domain.Member;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

// Withdrawn members are treated as missing everywhere in community.
public interface CommunityMemberRepository extends Repository<Member, Long> {
    boolean existsByIdAndWithdrawnAtIsNull(Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Member m where m.id = :id and m.withdrawnAt is null")
    Optional<Member> lockById(@Param("id") Long id);
}
