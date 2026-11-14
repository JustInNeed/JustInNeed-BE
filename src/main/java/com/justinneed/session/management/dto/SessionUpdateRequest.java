package com.justinneed.session.management.dto;

import com.justinneed.session.analysis.EssayMarkdownConverter;
import jakarta.validation.constraints.Size;
import java.util.List;

public record SessionUpdateRequest(
        @Size(max = EssayMarkdownConverter.MAX_TITLE_LENGTH, message = "제목은 최대 100자까지 입력할 수 있습니다.")
        String title,
        String editedMarkdown,
        Boolean isPublic,
        Boolean isFavorite,
        List<String> tags,
        // 편집 중 30초 자동 저장. 제목 · 본문만 바꾸는 임시 저장이면 커뮤니티 카드는 편집을 끝낼 때 맞춘다 (QA-030)
        Boolean draft
) {

    // 즐겨찾기 말고 세션 내용을 바꾸는 필드가 있는지. 분석이 끝나기 전에는 AI 결과에 덮이므로 받지 않는다
    public boolean changesContent() {
        return title != null || editedMarkdown != null || isPublic != null || tags != null;
    }

    // 카드를 다시 쓸 필요가 있는지. 자동 저장이 제목 · 본문만 바꾸면 미룬다 (공개 여부 · 태그가 바뀌면 바로 맞춘다)
    public boolean syncsCard() {
        boolean textOnlyDraft = Boolean.TRUE.equals(draft) && isPublic == null && tags == null;
        return changesContent() && !textOnlyDraft;
    }
}
