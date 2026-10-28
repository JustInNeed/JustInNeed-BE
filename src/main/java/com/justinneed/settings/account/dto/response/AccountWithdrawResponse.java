package com.justinneed.settings.account.dto.response;

import java.time.LocalDateTime;

public record AccountWithdrawResponse(
        int code,
        Long userId,
        boolean withdrawn,
        boolean extensionLogin,
        LocalDateTime withdrawnAt
) {
}
