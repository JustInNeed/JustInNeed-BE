package com.justinneed.session.analysis.dto;

import java.util.Map;

// AI → Spring GET /internal/ai/sessions/{sessionId}/input 응답. 이전 세션 유닛 피처는 재학습 때만 /history 로 따로 받는다
public record AiAnalysisInput(
        Long sessionId,
        Long userId,
        // 합친 원본 (session_raw_payloads.payload)
        Map<String, Object> session,
        // 현재 세션을 뺀 삭제되지 않은 결과 수
        long previousSessions,
        Map<String, Object> userModel,
        Map<String, Object> globalModel
) {
}
