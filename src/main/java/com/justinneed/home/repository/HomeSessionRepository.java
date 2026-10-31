package com.justinneed.home.repository;

import com.justinneed.session.management.domain.BrowsingSession;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface HomeSessionRepository extends Repository<BrowsingSession, Long> {

    // 내 완료 세션 전체, 시작 시각 최신순. 쓰는 컬럼만 읽어 unit_features · selected_units · analysis_units · 요약은 조회하지 않는다.
    // 분석 결과 행이 없는 완료 세션도 세션 통계에 들어가므로 left join 한다
    @Query("""
            select new com.justinneed.home.repository.HomeSessionRow(
                       session.id,
                       session.title,
                       session.startedAt,
                       session.endedAt,
                       session.pageCount,
                       session.favorite,
                       session.publicSession,
                       session.tags,
                       result.mindmap,
                       result.analysisSummary,
                       result.pages)
            from BrowsingSession session
            left join SessionResult result on result.sessionId = session.id
            where session.userId = :userId
              and session.status = com.justinneed.session.management.domain.SessionStatus.COMPLETED
              and session.deletedAt is null
            order by session.startedAt desc, session.id desc
            """)
    List<HomeSessionRow> findCompletedByUserId(@Param("userId") Long userId);
}
