package com.justinneed.session.analysis.dto;

// 세션 마인드맵 응답에 필요한 session_results 컬럼만 읽은 값. 저장된 AI JSON 이 Map · List 모양이 아니어도 읽히도록 문자열로 받는다
public record SessionMindmapSource(
        String mindmap,
        String selectedUnits
) {
}
