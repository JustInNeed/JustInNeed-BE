package com.justinneed.settings.integration.service;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.settings.integration.domain.ExternalIntegration;
import com.justinneed.settings.integration.domain.IntegrationProvider;
import com.justinneed.settings.integration.dto.response.IntegrationEmailRegisterResponse;
import com.justinneed.settings.integration.dto.response.IntegrationGuideResponse;
import com.justinneed.settings.integration.dto.response.IntegrationItemResponse;
import com.justinneed.settings.integration.dto.response.IntegrationStatusResponse;
import com.justinneed.settings.integration.repository.ExternalIntegrationRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ExternalIntegrationService {

    private final ExternalIntegrationRepository externalIntegrationRepository;
    private final Clock clock = Clock.systemDefaultZone();

    public ExternalIntegrationService(ExternalIntegrationRepository externalIntegrationRepository) {
        this.externalIntegrationRepository = externalIntegrationRepository;
    }

    public IntegrationStatusResponse getStatuses(Long memberId, String providerName) {
        Map<IntegrationProvider, ExternalIntegration> connectedByProvider = externalIntegrationRepository
                .findByMemberId(memberId)
                .stream()
                .collect(Collectors.toMap(ExternalIntegration::getProvider, Function.identity()));

        List<IntegrationProvider> providers = providerName == null
                ? Arrays.asList(IntegrationProvider.values())
                : List.of(IntegrationProvider.fromApiName(providerName));

        List<IntegrationItemResponse> integrations = providers.stream()
                .map(provider -> {
                    ExternalIntegration integration = connectedByProvider.get(provider);
                    return integration == null
                            ? IntegrationItemResponse.disconnected(provider)
                            : IntegrationItemResponse.connected(integration);
                })
                .toList();
        return new IntegrationStatusResponse(200, integrations);
    }

    public IntegrationGuideResponse getGuide(String providerName) {
        return IntegrationGuideResponse.from(IntegrationProvider.fromApiName(providerName));
    }

    @Transactional
    public IntegrationEmailRegisterResponse registerEmail(Long memberId, String providerName, String email) {
        IntegrationProvider provider = IntegrationProvider.fromApiName(providerName);
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        ExternalIntegration existing = externalIntegrationRepository
                .findByMemberIdAndProvider(memberId, provider)
                .orElse(null);

        boolean duplicate = existing == null
                ? externalIntegrationRepository.existsByProviderAndEmailIgnoreCase(provider, normalizedEmail)
                : externalIntegrationRepository.existsByProviderAndEmailIgnoreCaseAndIdNot(
                        provider, normalizedEmail, existing.getId());
        if (duplicate || existing != null && existing.getEmail().equalsIgnoreCase(normalizedEmail)) {
            throw new CustomException(ErrorCode.DUPLICATE_INTEGRATION_EMAIL);
        }

        LocalDateTime connectedAt = LocalDateTime.now(clock);
        ExternalIntegration integration;
        if (existing == null) {
            integration = new ExternalIntegration(memberId, provider, normalizedEmail, connectedAt);
        } else {
            existing.reconnect(normalizedEmail, connectedAt);
            integration = existing;
        }
        return IntegrationEmailRegisterResponse.from(externalIntegrationRepository.save(integration));
    }
}
