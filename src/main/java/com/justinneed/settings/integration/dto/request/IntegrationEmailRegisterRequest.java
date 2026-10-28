package com.justinneed.settings.integration.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record IntegrationEmailRegisterRequest(
        @NotBlank(message = "이메일을 입력해 주세요.")
        @Email(message = "올바른 이메일 형식이 아닙니다.")
        @Size(max = 254, message = "이메일은 254자 이하여야 합니다.")
        String email
) {
}
