package com.justinneed.session.management.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;
import org.jspecify.annotations.Nullable;

// 세션 출처는 링크(URL) 하나당 1개. visitedAt = 그 링크를 처음 방문한 시각 (KST). AI 분석 전에 만든 출처는 null
public record Source(
        @Nullable String title,
        String url,
        @Nullable String excerpt,
        @JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable LocalDateTime visitedAt) {

    public Source(String title, String url, String excerpt) {
        this(title, url, excerpt, null);
    }
}
