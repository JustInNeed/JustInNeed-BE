package com.justinneed.session.analysis.dto;

import java.util.List;
import java.util.Map;

// AI → Spring GET /internal/ai/sessions/{sessionId}/history 응답 (session_results.unit_features 를 모은 것)
public record AiSessionHistory(
        List<Map<String, Object>> unitFeatures
) {
}
