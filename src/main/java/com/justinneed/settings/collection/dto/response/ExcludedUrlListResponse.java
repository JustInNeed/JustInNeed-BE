package com.justinneed.settings.collection.dto.response;

import java.util.List;

public record ExcludedUrlListResponse(
        int code,
        List<ExcludedUrlItem> excludedUrls
) {
}
