package com.justinneed.settings.integration.dto.response;

import com.justinneed.settings.integration.domain.ExternalIntegration;
import java.time.LocalDateTime;

public record IntegrationEmailRegisterResponse(
        int code,
        String provider,
        String email,
        boolean isConnected,
        LocalDateTime connectedAt
) {

    public static IntegrationEmailRegisterResponse from(ExternalIntegration integration) {
        return new IntegrationEmailRegisterResponse(
                201,
                integration.getProvider().getApiName(),
                integration.getEmail(),
                true,
                integration.getConnectedAt()
        );
    }
}
