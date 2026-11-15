package com.justinneed.analytics.service;

import com.justinneed.analytics.dto.AnalyticsResponse.Note;
import com.justinneed.analytics.dto.AnalyticsResponse.TimePatterns;
import com.justinneed.analytics.repository.AnalyticsUnitsRow;
import com.justinneed.session.analysis.RawLinkVisits;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 시간대별 패턴 (ANA-2-4 · 2-5). 유닛 하나를 처음 본 시각(KST)의 칸에 1개로 센다 (Idle · 체류 0 포함).
 *
 * <pre>
 * surfing = 칸 유닛 수 ÷ 가장 많은 칸의 유닛 수
 * focus   = 칸의 Deep + Reading 유닛 수 ÷ 칸 유닛 수
 * </pre>
 *
 * 문구도 같은 유닛 수로 판단하고, 정독 시간대 후보를 거르는 최소 체류만 체류시간으로 본다.
 */
final class TimePatternAggregator {

    private static final int DAYS = 7;
    private static final int SLOT_HOURS = 3;
    private static final int SLOTS = 24 / SLOT_HOURS;
    private static final Set<String> FOCUSED_LABELS = Set.of("Deep Reading", "Reading");
    private static final String PEAK_SLOT = "웹 탐색은 주로 **%s**에 진행됩니다. 이 시간대에 가장 많은 정보 탐색과 읽기가 이루어지고 있어요.";
    private static final String NO_PEAK_SLOT = "특정 시간대에 집중되기보다, 하루 전반에 걸쳐 웹 탐색이 이루어지고 있어요. "
            + "집중이 잘 되는 시간대를 활용해 중요한 웹 탐색을 배치해보세요.";
    private static final String FOCUS_SLOT = "**%s**에 집중도가 가장 높아요. (정독 비율 %d%%로 가장 높은 시간대) 중요한 학습은 이 시간대를 활용해보세요.";

    private final long[][] unitCounts = new long[DAYS][SLOTS];
    private final long[][] focusedUnits = new long[DAYS][SLOTS];
    private final long[][] dwellMs = new long[DAYS][SLOTS];

    private TimePatternAggregator() {
    }

    static TimePatterns aggregate(List<AnalyticsUnitsRow> sessions) {
        TimePatternAggregator aggregator = new TimePatternAggregator();
        for (AnalyticsUnitsRow session : sessions) {
            if (session.units() != null) {
                session.units().forEach(aggregator::add);
            }
        }
        return aggregator.result();
    }

    private void add(Map<String, Object> unit) {
        LocalDateTime seenAt = RawLinkVisits.toKst(unit.get("firstSeenAt"));
        if (seenAt == null) {
            return;
        }
        long dwell = AnalyticsJson.nonNegative(unit.get("dwellMs"));
        int day = seenAt.getDayOfWeek().getValue() - 1;
        int slot = seenAt.getHour() / SLOT_HOURS;
        unitCounts[day][slot]++;
        dwellMs[day][slot] += dwell;
        if (FOCUSED_LABELS.contains(unit.get("clusterLabel"))) {
            focusedUnits[day][slot]++;
        }
    }

    private TimePatterns result() {
        long max = Arrays.stream(unitCounts).flatMapToLong(Arrays::stream).max().orElse(0);
        if (max == 0) {
            return null;
        }
        double[][] surfing = new double[DAYS][SLOTS];
        double[][] focus = new double[DAYS][SLOTS];
        for (int day = 0; day < DAYS; day++) {
            for (int slot = 0; slot < SLOTS; slot++) {
                surfing[day][slot] = ratio(unitCounts[day][slot], max);
                focus[day][slot] = ratio(focusedUnits[day][slot], unitCounts[day][slot]);
            }
        }
        return new TimePatterns(surfing, focus, note());
    }

    // 소수 둘째 자리까지. 분모가 0 이면 0
    private static double ratio(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 100.0 / whole) / 100.0;
    }

    // 문구는 요일을 합친 시간대(하루 8구간)로 판단한다
    private Note note() {
        long[] slotUnits = new long[SLOTS];
        long[] slotFocused = new long[SLOTS];
        long[] slotDwell = new long[SLOTS];
        for (int day = 0; day < DAYS; day++) {
            for (int slot = 0; slot < SLOTS; slot++) {
                slotUnits[slot] += unitCounts[day][slot];
                slotFocused[slot] += focusedUnits[day][slot];
                slotDwell[slot] += dwellMs[day][slot];
            }
        }
        return new Note(peakSentence(slotUnits), focusSentence(slotUnits, slotFocused, slotDwell));
    }

    // 유닛이 가장 많은 구간이 전체 유닛의 기준 % 이상이면 "주로" 문구
    private static String peakSentence(long[] slotUnits) {
        int peak = 0;
        for (int slot = 1; slot < SLOTS; slot++) {
            if (slotUnits[slot] > slotUnits[peak]) {
                peak = slot;
            }
        }
        long total = Arrays.stream(slotUnits).sum();
        return slotUnits[peak] * 100.0 / total >= AnalyticsRules.PEAK_SLOT_PCT
                ? PEAK_SLOT.formatted(slotLabel(peak))
                : NO_PEAK_SLOT;
    }

    // 체류가 충분한 구간 중 정독 비율(유닛 수)이 가장 높은 구간. 같으면 이른 구간, 정독이 없으면 문구 없음
    private static String focusSentence(long[] slotUnits, long[] slotFocused, long[] slotDwell) {
        int best = -1;
        double bestPct = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slotDwell[slot] < AnalyticsRules.MIN_FOCUS_SLOT_DWELL_MS) {
                continue;
            }
            double pct = slotFocused[slot] * 100.0 / slotUnits[slot];
            if (pct > bestPct) {
                best = slot;
                bestPct = pct;
            }
        }
        return best < 0 ? null : FOCUS_SLOT.formatted(slotLabel(best), Math.round(bestPct));
    }

    private static String slotLabel(int slot) {
        int start = slot * SLOT_HOURS;
        return clockLabel(start) + " ~ " + clockLabel((start + SLOT_HOURS) % 24);
    }

    private static String clockLabel(int hour) {
        if (hour < 12) {
            return "오전 " + hour + "시";
        }
        return "오후 " + (hour == 12 ? 12 : hour - 12) + "시";
    }
}
