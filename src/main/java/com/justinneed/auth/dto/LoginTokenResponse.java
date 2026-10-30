package com.justinneed.auth.dto;

// needsNickname: 닉네임을 아직 안 정한 회원(첫 가입)이면 true → FE 가 닉네임 입력 화면으로 보낸다
public record LoginTokenResponse(
        String accessToken,
        String refreshToken,
        boolean needsNickname
) {
}
