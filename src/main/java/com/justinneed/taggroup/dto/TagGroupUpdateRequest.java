package com.justinneed.taggroup.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record TagGroupUpdateRequest(
        @Size(max = 50, message = "그룹 이름은 최대 50자까지 입력할 수 있습니다.")
        String name,
        List<String> hashtags
) {
}
