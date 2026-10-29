package com.justinneed.session.management.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record SessionUpdateRequest(
        @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
        String title,
        String editedMarkdown,
        Boolean isPublic,
        Boolean isFavorite,
        List<String> tags
) {
}
