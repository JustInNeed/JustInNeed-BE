package com.justinneed.session.management.domain;

// 세션 상태 흐름: COLLECTING → READY → ANALYZING → COMPLETED / FAILED
public enum SessionStatus {
    // 수집 중: 시작 요청을 받았고 확장이 아직 청크를 보내는 중 (종료 요청 뒤 합치기 전까지 포함)
    COLLECTING,
    // 수집 완료: 청크를 합쳐 원본을 저장했고 AI 분석을 기다리는 중
    READY,
    ANALYZING,
    COMPLETED,
    FAILED
}
