package com.justinneed.home.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 홈 집계에 쓰는 완료 세션 한 줄. 세션 컬럼과 분석 결과(session_results) 컬럼 중 필요한 것만 담는다.
 * 분석 결과 행이 없는 세션은 mindmap · analysisSummary · pages 가 모두 null 이다.
 */
public record HomeSessionRow(
        Long id,
        String title,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        int pageCount,
        boolean favorite,
        boolean publicSession,
        List<String> tags,
        Map<String, Object> mindmap,
        Map<String, Object> analysisSummary,
        List<Map<String, Object>> pages
) {

    // 세션 시각은 KST 로 저장되므로 날짜를 그대로 쓴다
    public LocalDate startDate() {
        return startedAt.toLocalDate();
    }
}
