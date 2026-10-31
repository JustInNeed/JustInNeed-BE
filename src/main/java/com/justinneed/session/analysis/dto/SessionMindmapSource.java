package com.justinneed.session.analysis.dto;

import java.util.List;
import java.util.Map;

// 세션 마인드맵 응답에 필요한 session_results 컬럼만 읽은 값
public record SessionMindmapSource(
        Map<String, Object> mindmap,
        List<Map<String, Object>> selectedUnits
) {
}
