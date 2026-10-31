package com.justinneed.settings.extension.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.justinneed.settings.extension.domain.ExtensionSetting;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtensionSettingResponse(
        int code,
        Long userId,
        Boolean popupVisible,
        Boolean autoEndNotificationEnabled,
        Boolean manualScrapEnabled,
        LocalDateTime updatedAt
) {

    public static ExtensionSettingResponse of(Long userId, ExtensionSetting setting) {
        return new ExtensionSettingResponse(200, userId, setting.isPopupVisible(),
                setting.isAutoEndNotificationEnabled(), setting.isManualScrapEnabled(), setting.getUpdatedAt());
    }

    public static ExtensionSettingResponse popup(Long userId, boolean enabled, LocalDateTime updatedAt) {
        return new ExtensionSettingResponse(200, userId, enabled, null, null, updatedAt);
    }

    public static ExtensionSettingResponse autoEndNotification(Long userId, boolean enabled, LocalDateTime updatedAt) {
        return new ExtensionSettingResponse(200, userId, null, enabled, null, updatedAt);
    }

    public static ExtensionSettingResponse manualScrap(Long userId, boolean enabled, LocalDateTime updatedAt) {
        return new ExtensionSettingResponse(200, userId, null, null, enabled, updatedAt);
    }
}
