package com.justinneed.analytics.repository;

import java.util.List;
import java.util.Map;

/** 세션 하나의 전체 유닛 (analysis_units). 시간대 집계에만 쓴다 */
public record AnalyticsUnitsRow(List<Map<String, Object>> units) {
}
