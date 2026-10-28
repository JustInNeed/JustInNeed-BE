package com.justinneed.settings.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AccountWithdrawRequest(
        @NotBlank(message = "탈퇴 확인 문구를 입력해 주세요.")
        String confirmText,
        @Size(max = 500, message = "탈퇴 사유는 최대 500자까지 입력할 수 있습니다.")
        String reason
) {
}
