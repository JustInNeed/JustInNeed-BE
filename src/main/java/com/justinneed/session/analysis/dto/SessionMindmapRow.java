package com.justinneed.session.analysis.dto;

// 여러 세션의 마인드맵을 한 번에 읽을 때의 행. 컬럼은 SessionMindmapSource 와 같다
public record SessionMindmapRow(
        Long sessionId,
        String mindmap,
        String selectedUnits
) {
}
