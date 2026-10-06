package com.justinneed.settings.collection.dto.response;

import java.util.List;

public record ExcludedUrlDeleteResponse(
        int code,
        List<Long> deletedUrlIds,
        List<Long> uncheckedDefaultUrlIds,
        int deletedCount
) {
}
