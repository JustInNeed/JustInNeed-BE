package com.justinneed.auth.dto;

public record TokenRefreshRequest(
        String refreshToken
) {
}
