package com.justinneed.auth.dto;

// refreshToken 은 있으면 같이 무효화, 없으면 access 토큰만 무효화
public record LogoutRequest(
        String refreshToken
) {
}
