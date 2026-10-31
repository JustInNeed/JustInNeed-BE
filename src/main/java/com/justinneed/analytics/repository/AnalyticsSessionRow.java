package com.justinneed.analytics.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 분석 집계에 쓰는 완료 세션 한 줄. 분석 결과 행이 없는 세션은 analysisSummary 가 null 이고,
 * AI 가 pageVisits 를 주기 전에 분석한 세션은 pageVisits 가 null 이다.
 */
public record AnalyticsSessionRow(
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        int pageCount,
        Map<String, Object> analysisSummary,
        List<Map<String, Object>> pageVisits
) {
}
