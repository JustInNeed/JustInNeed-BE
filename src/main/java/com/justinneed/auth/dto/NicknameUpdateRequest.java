package com.justinneed.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// FE(NicknameStep)와 동일한 규칙: 영문으로 시작하는 3~8자(영문·숫자)
public record NicknameUpdateRequest(
        @NotBlank(message = "닉네임을 입력해 주세요.")
        // 빈 값은 위 문구 하나만 나가도록 형식 검사에서는 통과시킨다
        @Pattern(regexp = "^\\s*$|^[a-zA-Z][a-zA-Z0-9]{2,7}$",
                message = "닉네임은 영문으로 시작하는 3~8자(영문·숫자)여야 합니다.")
        String nickname
) {
}
