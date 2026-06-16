package com.justinneed.auth.dto;

// 닉네임 유효성 검사는 추후 추가 예정 (지금은 검증 없음)
public record NicknameUpdateRequest(
        String nickname
) {
}
