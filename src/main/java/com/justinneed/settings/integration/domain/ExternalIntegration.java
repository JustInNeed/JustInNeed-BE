package com.justinneed.settings.integration.domain;

import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "external_integrations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_external_integrations_member_provider",
                        columnNames = {"member_id", "provider"}
                ),
                @UniqueConstraint(
                        name = "uk_external_integrations_provider_email",
                        columnNames = {"provider", "email"}
                )
        }
)
public class ExternalIntegration extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private IntegrationProvider provider;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "connected_at", nullable = false)
    private LocalDateTime connectedAt;

    protected ExternalIntegration() {
    }

    public ExternalIntegration(Long memberId, IntegrationProvider provider, String email, LocalDateTime connectedAt) {
        this.memberId = memberId;
        this.provider = provider;
        this.email = email;
        this.connectedAt = connectedAt;
    }

    public void reconnect(String email, LocalDateTime connectedAt) {
        this.email = email;
        this.connectedAt = connectedAt;
    }

    public Long getId() {
        return id;
    }

    public IntegrationProvider getProvider() {
        return provider;
    }

    public String getEmail() {
        return email;
    }

    public LocalDateTime getConnectedAt() {
        return connectedAt;
    }
}
