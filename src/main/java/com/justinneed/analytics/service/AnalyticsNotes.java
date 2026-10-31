package com.justinneed.analytics.service;

import com.justinneed.analytics.ReadingDistribution;
import com.justinneed.analytics.ReadingDistribution.Level;
import com.justinneed.analytics.dto.AnalyticsResponse.Note;
import com.justinneed.analytics.dto.AnalyticsResponse.ReadingBehavior;
import java.util.LinkedHashMap;
import java.util.Map;

/** 읽기 행동 카드 문구 (ANA-3-2). Deep Reading · Scanning 은 엑셀, Idle 은 엑셀의 Navigation 문구, Reading 은 결정 11 문구 */
final class AnalyticsNotes {

    private static final Map<Level, String> READING_TENDENCY = Map.of(
            Level.DEEP_READING, "한 번 읽기 시작하면 깊이 있게 이해하는 편입니다.",
            Level.READING, "글의 핵심을 차근차근 읽어 나가는 편입니다.",
            Level.SCANNING, "전체를 빠르게 훑으며 핵심을 찾는 경향이 있습니다.",
            Level.IDLE, "정보를 찾기 위해 페이지 간 이동이 많은 탐색형 패턴입니다.");

    private AnalyticsNotes() {
    }

    static ReadingBehavior readingBehavior(ReadingDistribution distribution) {
        Map<Level, Integer> shares = distribution.shares();
        Map<String, Integer> sharesByName = new LinkedHashMap<>();
        shares.forEach((level, pct) -> sharesByName.put(level.name(), pct));

        Level dominant = distribution.dominant();
        String analysis = "%s **%s 비중 %d%%**로 가장 높아요."
                .formatted(READING_TENDENCY.get(dominant), dominant.label(), shares.get(dominant));
        return new ReadingBehavior(sharesByName, new Note(analysis, null));
    }
}
