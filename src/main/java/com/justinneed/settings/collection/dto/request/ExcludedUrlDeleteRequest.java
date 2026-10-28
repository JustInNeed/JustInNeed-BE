package com.justinneed.settings.collection.dto.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ExcludedUrlDeleteRequest(
        @NotEmpty(message = "삭제할 URL을 선택해 주세요.")
        List<Long> excludedUrlIds
) {
}
