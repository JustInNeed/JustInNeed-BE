package com.justinneed.community.dto;

import com.justinneed.session.management.domain.SessionStatus;

// 고정할 수 있는지 판단하는 값만 읽은 행 (요약 · 출처는 읽지 않는다)
public record PinCandidateRow(
        Long id,
        SessionStatus status,
        boolean publicSession
) {
}
