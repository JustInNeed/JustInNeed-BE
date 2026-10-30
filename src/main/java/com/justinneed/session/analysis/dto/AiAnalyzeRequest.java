package com.justinneed.session.analysis.dto;

import java.util.List;
import java.util.Map;

// Spring → AI POST /analyze. 결과는 AI 가 /internal/ai/sessions/{sessionId}/result 로 보내온다
public record AiAnalyzeRequest(
        // Spring 세션 id (AI 가 콜백 주소에 쓴다)
        Long sessionId,
        // stepC 원본 (session_raw_payloads.payload)
        Map<String, Object> session,
        // 같은 사용자의 이전 세션 유닛 피처 (session_results.unit_features 를 모은 것)
        List<Map<String, Object>> history,
        Map<String, Object> userModel,
        Map<String, Object> globalModel
) {
}
