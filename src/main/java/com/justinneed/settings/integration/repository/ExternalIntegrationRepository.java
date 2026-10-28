package com.justinneed.settings.integration.repository;

import com.justinneed.settings.integration.domain.ExternalIntegration;
import com.justinneed.settings.integration.domain.IntegrationProvider;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalIntegrationRepository extends JpaRepository<ExternalIntegration, Long> {

    List<ExternalIntegration> findByMemberId(Long memberId);

    Optional<ExternalIntegration> findByMemberIdAndProvider(Long memberId, IntegrationProvider provider);

    boolean existsByProviderAndEmailIgnoreCase(IntegrationProvider provider, String email);

    boolean existsByProviderAndEmailIgnoreCaseAndIdNot(
            IntegrationProvider provider,
            String email,
            Long id
    );
}
