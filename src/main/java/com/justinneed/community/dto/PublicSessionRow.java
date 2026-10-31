package com.justinneed.community.dto;

import java.util.List;

// 공개 세션 판단에 필요한 컬럼만 읽은 행 (요약 · 출처는 읽지 않는다)
public record PublicSessionRow(
        Long id,
        Long userId,
        List<String> tags
) {
}
