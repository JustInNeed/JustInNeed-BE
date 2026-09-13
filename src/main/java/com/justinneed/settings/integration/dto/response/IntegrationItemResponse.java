package com.justinneed.settings.integration.dto.response;

import com.justinneed.settings.integration.domain.ExternalIntegration;
import com.justinneed.settings.integration.domain.IntegrationProvider;
import java.time.LocalDateTime;

public record IntegrationItemResponse(
        String provider,
        boolean isConnected,
        String email,
        LocalDateTime connectedAt
) {

    public static IntegrationItemResponse disconnected(IntegrationProvider provider) {
        return new IntegrationItemResponse(provider.getApiName(), false, null, null);
    }

    public static IntegrationItemResponse connected(ExternalIntegration integration) {
        return new IntegrationItemResponse(
                integration.getProvider().getApiName(),
                true,
                integration.getEmail(),
                integration.getConnectedAt()
        );
    }
}
