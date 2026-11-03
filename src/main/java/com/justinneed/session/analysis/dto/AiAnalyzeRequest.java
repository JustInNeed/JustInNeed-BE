package com.justinneed.session.analysis.dto;

// Spring → AI POST /analyze. 합치기가 끝났다는 알림이다. AI 는 GET /internal/ai/sessions/{sessionId}/input 으로 값을 가져온다
public record AiAnalyzeRequest(
        Long sessionId
) {
}
