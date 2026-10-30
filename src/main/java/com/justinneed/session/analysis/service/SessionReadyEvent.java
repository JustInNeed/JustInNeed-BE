package com.justinneed.session.analysis.service;

// 세션 수집이 끝나 READY 가 됨 → 커밋 뒤 AI 분석 요청
public record SessionReadyEvent(
        Long sessionId
) {
}
