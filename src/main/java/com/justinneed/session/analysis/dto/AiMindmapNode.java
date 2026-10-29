package com.justinneed.session.analysis.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// type 은 "root" / "topic" / "unit". score 는 null 일 수 있다.
@JsonIgnoreProperties(ignoreUnknown = true)
public record AiMindmapNode(
        String id,
        String type,
        String keyword,
        Double score,
        Integer childCount
) {

    public static final String TYPE_ROOT = "root";
    public static final String TYPE_TOPIC = "topic";
    public static final String TYPE_UNIT = "unit";
}
