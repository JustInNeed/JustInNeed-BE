package com.justinneed.settings.collection.dto.response;

import java.util.List;

public record ExcludedUrlListResponse(
        int code,
        List<ExcludedUrlItem> excludedUrls,
        int page,
        int size,
        boolean hasNext
) {
}
