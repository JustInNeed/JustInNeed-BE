package com.justinneed.auth.dto;

public record TokenResponse(
        String accessToken,
        String refreshToken
) {
}
