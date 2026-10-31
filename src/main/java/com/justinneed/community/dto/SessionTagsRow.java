package com.justinneed.community.dto;

import java.util.List;

// 세션의 작성자 · 태그 컬럼만 읽은 행 (요약 · 출처는 읽지 않는다)
public record SessionTagsRow(
        Long userId,
        List<String> tags
) {
}
