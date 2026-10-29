package com.justinneed.session.analysis.repository;

import com.justinneed.session.analysis.domain.SessionResult;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionResultRepository extends JpaRepository<SessionResult, Long> {

    // AI 가 다음 세션을 분석할 때 이 사용자의 이전 세션 유닛 피처를 모두 읽는다
    List<SessionResult> findByUserId(Long userId);

    long countByUserId(Long userId);
}
