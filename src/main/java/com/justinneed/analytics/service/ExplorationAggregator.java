package com.justinneed.analytics.service;

import com.justinneed.analytics.ExplorationType;
import com.justinneed.analytics.dto.AnalyticsResponse.Exploration;
import com.justinneed.analytics.dto.AnalyticsResponse.Note;
import com.justinneed.analytics.repository.AnalyticsSessionRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 탐색 성향 카드 (ANA-4, ANA-2-3).
 *
 * <pre>
 * 세션 평균 URL  = 세션별 방문 링크 수의 평균
 * 평균 페이지 체류 = 세션별 (페이지 체류시간 합 ÷ 페이지 수) 의 평균. pageVisits 가 없는 세션은 뺀다
 * 평균 세션 길이 = 종료 시각 - 시작 시각의 평균. 종료 시각이 없는 세션은 뺀다
 * 탐색 성향     = 세션별로 판정해 가장 많이 나온 성향. pageVisits 가 없는 세션은 뺀다
 * </pre>
 */
final class ExplorationAggregator {

    private static final String NOT_JUDGED = "아직 탐색 성향을 판정할 데이터가 부족해요.";
    private static final String[] FOCUSED_LABELS = {"Deep Reading", "Reading"};
    private static final String[] ALL_LABELS = {"Deep Reading", "Reading", "Scanning", "Idle"};
    private static final double MILLIS_PER_SECOND = 1000.0;

    private ExplorationAggregator() {
    }

    static Exploration aggregate(List<AnalyticsSessionRow> sessions) {
        if (sessions.isEmpty()) {
            return null;
        }
        List<Double> dwellSeconds = new ArrayList<>();
        Map<ExplorationType, Integer> typeCounts = new EnumMap<>(ExplorationType.class);
        for (AnalyticsSessionRow session : sessions) {
            if (hasPageVisits(session)) {
                double avgDwellSec = avgPageDwellSec(session);
                dwellSeconds.add(avgDwellSec);
                typeCounts.merge(judge(session.pageCount(), avgDwellSec, focusedPct(session)), 1, Integer::sum);
            }
        }
        ExplorationType type = mostCommon(typeCounts);
        return new Exploration(
                (int) Math.round(sessions.stream().mapToInt(AnalyticsSessionRow::pageCount).average().orElse(0)),
                (int) Math.round(dwellSeconds.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                (int) Math.round(avgSessionMinutes(sessions)),
                type,
                type == null ? new Note(NOT_JUDGED, null) : new Note(type.analysis(), type.recommendation()));
    }

    // 위에서부터 먼저 맞는 성향
    private static ExplorationType judge(int urls, double avgDwellSec, double focusedPct) {
        if (urls >= AnalyticsRules.MANY_URLS && avgDwellSec < AnalyticsRules.SHORT_DWELL_SEC) {
            return ExplorationType.DISTRACTED;
        }
        if (focusedPct >= AnalyticsRules.FOCUSED_PCT) {
            return ExplorationType.FOCUSED;
        }
        return urls >= AnalyticsRules.MANY_URLS ? ExplorationType.BROAD : ExplorationType.QUICK_CHECK;
    }

    private static boolean hasPageVisits(AnalyticsSessionRow session) {
        return session.pageVisits() != null && !session.pageVisits().isEmpty();
    }

    private static double avgPageDwellSec(AnalyticsSessionRow session) {
        long dwellMs = session.pageVisits().stream()
                .mapToLong(page -> AnalyticsJson.nonNegative(page.get("dwellMs")))
                .sum();
        return dwellMs / MILLIS_PER_SECOND / session.pageVisits().size();
    }

    // Deep + Reading 체류시간 ÷ 전체 체류시간. 요약이 없으면 0
    private static double focusedPct(AnalyticsSessionRow session) {
        long total = dwellMs(session, ALL_LABELS);
        return total == 0 ? 0 : dwellMs(session, FOCUSED_LABELS) * 100.0 / total;
    }

    private static long dwellMs(AnalyticsSessionRow session, String[] labels) {
        if (session.analysisSummary() == null) {
            return 0;
        }
        return Arrays.stream(labels)
                .mapToLong(label -> session.analysisSummary().get(label) instanceof Map<?, ?> entry
                        ? AnalyticsJson.nonNegative(entry.get("dwellMs"))
                        : 0)
                .sum();
    }

    private static double avgSessionMinutes(List<AnalyticsSessionRow> sessions) {
        return sessions.stream()
                .filter(session -> session.endedAt() != null)
                .mapToLong(session -> Duration.between(session.startedAt(), session.endedAt()).toSeconds())
                .average()
                .orElse(0) / 60;
    }

    // 같은 세션 수면 enum 앞쪽 성향
    private static ExplorationType mostCommon(Map<ExplorationType, Integer> typeCounts) {
        ExplorationType best = null;
        int bestCount = 0;
        for (Map.Entry<ExplorationType, Integer> entry : typeCounts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }
}
