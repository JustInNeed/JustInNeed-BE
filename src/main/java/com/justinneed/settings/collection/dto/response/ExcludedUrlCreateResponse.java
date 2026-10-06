package com.justinneed.settings.collection.dto.response;

import java.time.LocalDateTime;

public record ExcludedUrlCreateResponse(
        int code,
        Long excludedUrlId,
        String url,
        String domain,
        boolean isDefault,
        boolean isExcluded,
        LocalDateTime createdAt
) {
}
