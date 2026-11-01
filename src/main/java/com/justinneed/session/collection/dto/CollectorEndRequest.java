package com.justinneed.session.collection.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record CollectorEndRequest(
        @NotNull(message = "종료 시각(endedAt)을 입력해 주세요.")
        Instant endedAt,

        @NotBlank(message = "종료 사유(stopReason)를 입력해 주세요.")
        @Size(max = 50, message = "종료 사유(stopReason)는 50자 이하여야 합니다.")
        String stopReason,

        @NotNull(message = "총 청크 수(totalChunks)를 입력해 주세요.")
        @Min(value = 0, message = "총 청크 수(totalChunks)는 0 이상이어야 합니다.")
        Integer totalChunks
) {
}
