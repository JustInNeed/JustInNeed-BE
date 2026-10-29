package com.justinneed.session.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// AI 분석 결과의 essay. source 는 "llm" 또는 "fallback".
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiEssay(
        String title,
        List<AiEssaySection> sections,
        String source
) {
}
