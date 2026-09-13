package com.justinneed.settings.extension.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.settings.extension.dto.ExtensionSettingResponse;
import com.justinneed.settings.extension.dto.ExtensionToggleUpdateRequest;
import com.justinneed.settings.extension.service.ExtensionSettingService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/settings/extension")
public class ExtensionSettingController {

    private final ExtensionSettingService extensionSettingService;

    public ExtensionSettingController(ExtensionSettingService extensionSettingService) {
        this.extensionSettingService = extensionSettingService;
    }

    @PatchMapping("/popup-visible")
    public ApiResponse<ExtensionSettingResponse> updatePopupVisible(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ExtensionToggleUpdateRequest request
    ) {
        return ApiResponse.ok(extensionSettingService.updatePopupVisible(memberId, request.enabled()));
    }

    @PatchMapping("/auto-end-notification")
    public ApiResponse<ExtensionSettingResponse> updateAutoEndNotification(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ExtensionToggleUpdateRequest request
    ) {
        return ApiResponse.ok(extensionSettingService.updateAutoEndNotification(memberId, request.enabled()));
    }

    @PatchMapping("/manual-scrap")
    public ApiResponse<ExtensionSettingResponse> updateManualScrap(
            @AuthenticationPrincipal Long memberId,
            @Valid @RequestBody ExtensionToggleUpdateRequest request
    ) {
        return ApiResponse.ok(extensionSettingService.updateManualScrap(memberId, request.enabled()));
    }
}
