package com.justinneed.session.management.domain;

// 세션 상태 흐름: COLLECTING → READY → ANALYZING → COMPLETED / FAILED
public enum SessionStatus {
    // 수집 중: 확장이 아직 청크를 보내는 중
    COLLECTING,
    // 수집 완료: 청크를 다 받아 AI 분석을 기다리는 중
    READY,
    ANALYZING,
    COMPLETED,
    FAILED
}
