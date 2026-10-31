package com.justinneed.analytics;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 읽기 상태별 유닛 수 합 (analysis_summary). 비율 = 분류별 유닛 수 ÷ 전체 유닛 수 (Idle 포함).
 * 홈 Deep Reading 비율과 분석 읽기 행동 분포가 같은 숫자를 내도록 둘 다 이 계산을 쓴다.
 */
public final class ReadingDistribution {

    /** AI 군집 이름과 FE ReadingLevel. 같은 비율이면 앞에 있는 분류를 가장 높다고 본다 */
    public enum Level {
        DEEP_READING("Deep Reading"),
        READING("Reading"),
        SCANNING("Scanning"),
        IDLE("Idle");

        private final String label;

        Level(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    private final Map<Level, Long> units;

    private ReadingDistribution(Map<Level, Long> units) {
        this.units = units;
    }

    /** 분석 결과가 없는 세션의 null 은 건너뛴다. 형식이 어긋난 값은 0 으로 본다 */
    public static ReadingDistribution ofSummaries(Collection<Map<String, Object>> summaries) {
        Map<Level, Long> units = new LinkedHashMap<>();
        for (Level level : Level.values()) {
            units.put(level, 0L);
        }
        for (Map<String, Object> summary : summaries) {
            if (summary != null) {
                units.replaceAll((level, sum) -> sum + unitsOf(summary, level));
            }
        }
        return new ReadingDistribution(units);
    }

    private static long unitsOf(Map<String, Object> summary, Level level) {
        return summary.get(level.label()) instanceof Map<?, ?> entry && entry.get("units") instanceof Number number
                ? Math.max(0, number.longValue())
                : 0;
    }

    public boolean hasData() {
        return units.values().stream().mapToLong(Long::longValue).sum() > 0;
    }

    /** 합이 100 이 되는 정수 비율 */
    public Map<Level, Integer> shares() {
        List<Level> levels = List.of(Level.values());
        int[] percents = percentagesOf(levels.stream().mapToLong(units::get).toArray());
        Map<Level, Integer> shares = new LinkedHashMap<>();
        for (int i = 0; i < percents.length; i++) {
            shares.put(levels.get(i), percents[i]);
        }
        return shares;
    }

    public int deepReadingPct() {
        return shares().get(Level.DEEP_READING);
    }

    /** 유닛이 가장 많은 분류 */
    public Level dominant() {
        return Arrays.stream(Level.values())
                .max(Comparator.<Level>comparingLong(units::get).thenComparing(Comparator.reverseOrder()))
                .orElseThrow();
    }

    /** 합이 100 이 되는 정수 비율. 나머지가 큰 항목부터 1 씩 올리고, 전체가 0 이면 모두 0 */
    public static int[] percentagesOf(long[] counts) {
        long total = Arrays.stream(counts).sum();
        int[] percents = new int[counts.length];
        if (total == 0) {
            return percents;
        }
        List<Integer> byRemainder = new ArrayList<>();
        int assigned = 0;
        for (int i = 0; i < counts.length; i++) {
            percents[i] = (int) (counts[i] * 100 / total);
            assigned += percents[i];
            byRemainder.add(i);
        }
        byRemainder.sort(Comparator.<Integer>comparingLong(i -> counts[i] * 100 % total).reversed());
        for (int i = 0; i < 100 - assigned; i++) {
            percents[byRemainder.get(i)]++;
        }
        return percents;
    }
}
