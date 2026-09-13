package com.justinneed.settings.integration.dto.response;

import com.justinneed.settings.integration.domain.IntegrationProvider;
import java.util.List;

public record IntegrationGuideResponse(
        int code,
        String provider,
        String guideTitle,
        List<String> guideSteps,
        String guideUrl
) {

    public static IntegrationGuideResponse from(IntegrationProvider provider) {
        return new IntegrationGuideResponse(
                200,
                provider.getApiName(),
                provider.getGuideTitle(),
                provider.getGuideSteps(),
                "/settings/integrations/" + provider.getApiName() + "/guide"
        );
    }
}
