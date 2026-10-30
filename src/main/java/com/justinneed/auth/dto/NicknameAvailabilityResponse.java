package com.justinneed.auth.dto;

public record NicknameAvailabilityResponse(
        String nickname,
        boolean available
) {
}
