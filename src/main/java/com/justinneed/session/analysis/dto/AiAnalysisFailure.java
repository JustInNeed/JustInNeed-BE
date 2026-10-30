package com.justinneed.session.analysis.dto;

// AI → Spring 분석 실패 콜백
public record AiAnalysisFailure(
        String reason
) {
}
