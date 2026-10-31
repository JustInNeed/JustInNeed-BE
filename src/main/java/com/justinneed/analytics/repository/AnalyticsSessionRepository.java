package com.justinneed.analytics.repository;

import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.dto.SessionListResponse;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** 대상은 모두 내 완료 · 미삭제 세션이고, 시작 시각이 [from, to) 안에 있는 것만 읽는다 */
public interface AnalyticsSessionRepository extends Repository<BrowsingSession, Long> {

    @Query("""
            select new com.justinneed.session.management.dto.SessionListResponse(
                       session.id,
                       session.title,
                       session.startedAt,
                       session.endedAt,
                       session.pageCount,
                       session.status,
                       session.favorite,
                       session.publicSession,
                       session.tags)
            from BrowsingSession session
            where session.userId = :userId
              and session.status = com.justinneed.session.management.domain.SessionStatus.COMPLETED
              and session.deletedAt is null
              and session.startedAt >= :from
              and session.startedAt < :to
            order by session.startedAt desc, session.id desc
            """)
    List<SessionListResponse> findWeekSessions(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    // 세션 통계에는 분석 결과 행이 없는 세션도 들어가므로 left join 한다. 무거운 analysis_units 는 읽지 않는다
    @Query("""
            select new com.justinneed.analytics.repository.AnalyticsSessionRow(
                       session.startedAt,
                       session.endedAt,
                       session.pageCount,
                       result.analysisSummary,
                       result.pageVisits)
            from BrowsingSession session
            left join SessionResult result on result.sessionId = session.id
            where session.userId = :userId
              and session.status = com.justinneed.session.management.domain.SessionStatus.COMPLETED
              and session.deletedAt is null
              and session.startedAt >= :from
              and session.startedAt < :to
            order by session.startedAt desc, session.id desc
            """)
    List<AnalyticsSessionRow> findSessions(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );

    @Query("""
            select new com.justinneed.analytics.repository.AnalyticsUnitsRow(result.analysisUnits)
            from BrowsingSession session
            join SessionResult result on result.sessionId = session.id
            where session.userId = :userId
              and session.status = com.justinneed.session.management.domain.SessionStatus.COMPLETED
              and session.deletedAt is null
              and session.startedAt >= :from
              and session.startedAt < :to
            """)
    List<AnalyticsUnitsRow> findUnits(
            @Param("userId") Long userId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to
    );
}
