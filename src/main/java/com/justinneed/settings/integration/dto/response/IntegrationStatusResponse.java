package com.justinneed.settings.integration.dto.response;

import java.util.List;

public record IntegrationStatusResponse(
        int code,
        List<IntegrationItemResponse> integrations
) {
}
