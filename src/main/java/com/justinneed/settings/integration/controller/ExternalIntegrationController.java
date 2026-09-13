package com.justinneed.settings.integration.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.settings.integration.dto.request.IntegrationEmailRegisterRequest;
import com.justinneed.settings.integration.dto.response.IntegrationEmailRegisterResponse;
import com.justinneed.settings.integration.dto.response.IntegrationGuideResponse;
import com.justinneed.settings.integration.dto.response.IntegrationStatusResponse;
import com.justinneed.settings.integration.service.ExternalIntegrationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings/integrations")
public class ExternalIntegrationController {

    private final ExternalIntegrationService externalIntegrationService;

    public ExternalIntegrationController(ExternalIntegrationService externalIntegrationService) {
        this.externalIntegrationService = externalIntegrationService;
    }

    @GetMapping
    public ApiResponse<IntegrationStatusResponse> getStatuses(
            @AuthenticationPrincipal Long memberId,
            @RequestParam(required = false) String provider
    ) {
        return ApiResponse.ok(externalIntegrationService.getStatuses(memberId, provider));
    }

    @GetMapping("/{provider}/guide")
    public ApiResponse<IntegrationGuideResponse> getGuide(@PathVariable String provider) {
        return ApiResponse.ok(externalIntegrationService.getGuide(provider));
    }

    @PostMapping("/{provider}/email")
    public ResponseEntity<ApiResponse<IntegrationEmailRegisterResponse>> registerEmail(
            @AuthenticationPrincipal Long memberId,
            @PathVariable String provider,
            @Valid @RequestBody IntegrationEmailRegisterRequest request
    ) {
        IntegrationEmailRegisterResponse response = externalIntegrationService
                .registerEmail(memberId, provider, request.email());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(response));
    }
}
