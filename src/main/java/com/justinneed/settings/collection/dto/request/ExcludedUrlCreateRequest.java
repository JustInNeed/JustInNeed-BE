package com.justinneed.settings.collection.dto.request;

import jakarta.validation.constraints.NotBlank;

public record ExcludedUrlCreateRequest(
        @NotBlank(message = "URL을 입력해 주세요.")
        String url
) {
}
