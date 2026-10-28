package com.justinneed.settings.collection.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record ExcludedUrlSelectionUpdateRequest(
        @NotEmpty(message = "변경할 URL을 선택해 주세요.")
        List<Long> excludedUrlIds,
        @NotNull(message = "수집 제외 적용 여부를 입력해 주세요.")
        Boolean isExcluded
) {
}
