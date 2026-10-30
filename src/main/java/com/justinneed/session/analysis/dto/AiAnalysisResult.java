package com.justinneed.session.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

// AI /analyze 응답 (pipeline/output.py build_output). 항목은 session_results 에 그대로 넣으므로 JSON 모양 그대로 받는다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiAnalysisResult(
        String modelSource,
        String scoreMethod,
        Map<String, Object> state,
        Map<String, Object> mindmap,
        Map<String, Object> essay,
        List<Map<String, Object>> selected,
        Map<String, Object> analysis,
        List<Map<String, Object>> pages
) {
}
