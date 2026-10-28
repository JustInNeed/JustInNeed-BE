package com.justinneed.settings.collection.repository;

import com.justinneed.settings.collection.domain.ExcludedUrl;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExcludedUrlRepository extends JpaRepository<ExcludedUrl, Long> {

    Page<ExcludedUrl> findByMemberIdOrderByExcludedDescDomainAscUrlAsc(Long memberId, Pageable pageable);

    Page<ExcludedUrl> findByMemberIdOrderByExcludedDescIdAsc(Long memberId, Pageable pageable);

    List<ExcludedUrl> findAllByMemberIdAndIdIn(Long memberId, Collection<Long> ids);

    boolean existsByMemberIdAndUrl(Long memberId, String url);

    long countByMemberId(Long memberId);
}
