package com.justinneed.session.collection.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CollectorStartRequest(
        @NotBlank(message = "수집기 세션 id 를 입력해 주세요.")
        @Size(max = 64, message = "수집기 세션 id 는 64자 이하여야 합니다.")
        String collectorSessionId,

        @NotNull(message = "시작 시각(startedAt)을 입력해 주세요.")
        Instant startedAt
) {
}
