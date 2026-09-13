package com.justinneed.settings.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.settings.integration.dto.response.IntegrationEmailRegisterResponse;
import com.justinneed.settings.integration.dto.response.IntegrationStatusResponse;
import com.justinneed.settings.integration.repository.ExternalIntegrationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ExternalIntegrationServiceTest {

    @Autowired
    private ExternalIntegrationService externalIntegrationService;

    @Autowired
    private ExternalIntegrationRepository externalIntegrationRepository;

    @BeforeEach
    void setUp() {
        externalIntegrationRepository.deleteAll();
    }

    @Test
    void returnsEveryProviderIncludingDisconnectedOnes() {
        externalIntegrationService.registerEmail(1L, "notion", "User@Example.com");

        IntegrationStatusResponse response = externalIntegrationService.getStatuses(1L, null);

        assertThat(response.integrations()).hasSize(3);
        assertThat(response.integrations().get(0).provider()).isEqualTo("notion");
        assertThat(response.integrations().get(0).isConnected()).isTrue();
        assertThat(response.integrations().get(0).email()).isEqualTo("user@example.com");
        assertThat(response.integrations().get(1).isConnected()).isFalse();
    }

    @Test
    void rejectsDuplicateEmailForSameProvider() {
        externalIntegrationService.registerEmail(1L, "notion", "user@example.com");

        assertThatThrownBy(() -> externalIntegrationService.registerEmail(2L, "notion", "USER@example.com"))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_INTEGRATION_EMAIL);
    }

    @Test
    void reconnectsProviderWithAChangedEmail() {
        externalIntegrationService.registerEmail(1L, "googleDocs", "old@example.com");

        IntegrationEmailRegisterResponse response = externalIntegrationService
                .registerEmail(1L, "googleDocs", "new@example.com");

        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(externalIntegrationRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsUnsupportedProvider() {
        assertThatThrownBy(() -> externalIntegrationService.getGuide("dropbox"))
                .isInstanceOf(CustomException.class)
                .extracting(exception -> ((CustomException) exception).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_INTEGRATION_PROVIDER);
    }
}
