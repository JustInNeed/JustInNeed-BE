package com.justinneed.auth.dto;

// 확장에 넘길 일회용 코드. expiresInSeconds 안에 POST /auth/token 으로 한 번만 바꿀 수 있다
public record ExtensionCodeResponse(
        String code,
        long expiresInSeconds
) {
}
