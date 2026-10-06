package com.justinneed.settings.account.dto.response;

import java.time.LocalDateTime;

public record NicknameUpdateResponse(
        int code,
        Long userId,
        String nickname,
        LocalDateTime updatedAt
) {
}
