package com.justinneed.session.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// essay 의 소제목 + 본문 한 덩어리. unitKeys 는 본문 근거가 된 마인드맵 유닛 노드 id 목록.
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiEssaySection(
        String heading,
        String body,
        List<String> unitKeys
) {
}
