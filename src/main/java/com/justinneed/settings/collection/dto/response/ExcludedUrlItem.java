package com.justinneed.settings.collection.dto.response;

import com.justinneed.settings.collection.domain.ExcludedUrl;

public record ExcludedUrlItem(
        Long excludedUrlId,
        String url,
        String domain,
        boolean isDefault,
        boolean isExcluded
) {
    public static ExcludedUrlItem from(ExcludedUrl excludedUrl) {
        return new ExcludedUrlItem(
                excludedUrl.getId(),
                excludedUrl.getUrl(),
                excludedUrl.getDomain(),
                excludedUrl.isDefaultUrl(),
                excludedUrl.isExcluded()
        );
    }
}
