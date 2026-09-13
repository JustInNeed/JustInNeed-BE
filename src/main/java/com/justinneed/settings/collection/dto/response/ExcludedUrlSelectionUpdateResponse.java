package com.justinneed.settings.collection.dto.response;

import java.util.List;

public record ExcludedUrlSelectionUpdateResponse(
        int code,
        int updatedCount,
        boolean isExcluded,
        List<Long> updatedUrlIds
) {
}
