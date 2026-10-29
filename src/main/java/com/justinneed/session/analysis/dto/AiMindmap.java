package com.justinneed.session.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

// AI 분석 결과의 mindmap 중 해시태그 선정에 필요한 노드 목록만 받는다. (edges 등 나머지는 무시)
// nodes 순서 = 원문 순서 (루트 → 주제(그룹 순) → 유닛(원문 순)).
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiMindmap(
        List<AiMindmapNode> nodes
) {
}
