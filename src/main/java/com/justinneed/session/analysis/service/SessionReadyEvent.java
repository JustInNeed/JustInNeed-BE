package com.justinneed.session.analysis.service;

// 세션이 READY 가 됨 (수집 완료 · 재분석) → 커밋 뒤 AI 분석 요청
public record SessionReadyEvent(
        Long sessionId
) {
}
