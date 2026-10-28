package com.justinneed.community.search;

import com.justinneed.auth.domain.Member;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface CommunityDirectoryRepository extends Repository<Member, Long> {
    @Query("""
        select m.id from Member m where m.id > :after
        and (exists (select p.userId from CommunityProfile p where p.userId = m.id)
          or exists (select s.id from BrowsingSession s where s.userId = m.id
                     and s.publicSession = true and s.deletedAt is null))
        order by m.id
        """)
    List<Long> candidates(@Param("after") Long after, Pageable page);
}
