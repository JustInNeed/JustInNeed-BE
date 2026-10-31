package com.justinneed.settings.extension.dto;

import jakarta.validation.constraints.NotNull;

public record ExtensionToggleUpdateRequest(
        @NotNull(message = "설정 값을 입력해 주세요.") Boolean enabled
) {
}
