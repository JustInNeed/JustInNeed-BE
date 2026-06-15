package com.justinneed.taggroup.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record TagGroupOrderRequest(
        @NotEmpty(message = "정렬할 그룹 목록을 입력해 주세요.")
        List<Long> groupIds
) {
}
