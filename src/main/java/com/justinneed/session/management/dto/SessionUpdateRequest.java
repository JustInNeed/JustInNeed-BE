package com.justinneed.session.management.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

public record SessionUpdateRequest(
        @Size(max = 100, message = "제목은 최대 100자까지 입력할 수 있습니다.")
        String title,
        String editedMarkdown,
        Boolean isPublic,
        Boolean isFavorite,
        List<String> tags,
        // 하이라이트(요약 insights). 보내면 전체 교체(텍스트 수정/개별 삭제/추가 모두 이 한 필드로 처리). null이면 변경 없음.
        List<String> insights
) {
}
