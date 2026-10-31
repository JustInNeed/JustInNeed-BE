package com.justinneed.settings.collection.repository;

import com.justinneed.settings.collection.domain.ExcludedUrl;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExcludedUrlRepository extends JpaRepository<ExcludedUrl, Long> {

    // 체크된 항목 먼저, 그 안에서 도메인 · URL 순. 그룹 묶기는 FE 가 이 순서를 받아서 한다
    List<ExcludedUrl> findByMemberIdOrderByExcludedDescDomainAscUrlAsc(Long memberId);

    List<ExcludedUrl> findAllByMemberIdAndIdIn(Long memberId, Collection<Long> ids);

    boolean existsByMemberIdAndUrl(Long memberId, String url);

    long countByMemberId(Long memberId);
}
