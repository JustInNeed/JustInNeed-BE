package com.justinneed.session.analysis.repository;

import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.dto.SessionMindmapSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionResultRepository extends JpaRepository<SessionResult, Long> {

    // AI 가 다음 세션을 분석할 때 이 사용자의 이전 세션 유닛 피처를 모두 읽는다. 지운 세션은 개인화에 쓰지 않는다
    @Query("""
            select result
            from SessionResult result
            where result.userId = :userId
              and not exists (select 1 from BrowsingSession session
                              where session.id = result.sessionId and session.deletedAt is not null)
            """)
    List<SessionResult> findActiveByUserId(@Param("userId") Long userId);

    long countByUserId(Long userId);

    // 세션 마인드맵 화면용. 유닛 피처 · 분석 유닛 등 큰 컬럼은 읽지 않는다
    @Query("""
            select new com.justinneed.session.analysis.dto.SessionMindmapSource(result.mindmap, result.selectedUnits)
            from SessionResult result
            where result.sessionId = :sessionId
            """)
    Optional<SessionMindmapSource> findMindmapSource(@Param("sessionId") Long sessionId);
}
