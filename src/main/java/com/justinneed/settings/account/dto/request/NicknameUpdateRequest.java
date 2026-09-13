package com.justinneed.settings.account.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record NicknameUpdateRequest(
        @NotBlank(message = "닉네임은 영문 1~8자로 입력해 주세요.")
        @Pattern(regexp = "^[A-Za-z]{1,8}$", message = "닉네임은 영문 1~8자로 입력해 주세요.")
        String nickname
) {
}
