package com.justinneed.settings.extension.dto;

import jakarta.validation.constraints.NotNull;

public record ExtensionToggleUpdateRequest(
        @NotNull Boolean enabled
) {
}
