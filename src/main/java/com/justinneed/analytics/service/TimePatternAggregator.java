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
 * 시간대별 패턴 (ANA-2-4 · 2-5). 재료는 유닛의 처음 본 시각(KST)과 체류시간이고, 유닛 체류시간은 처음 본 시각의 칸에 넣는다.
 *
 * <pre>
 * surfing = 칸 체류시간 ÷ 가장 많은 칸의 체류시간
 * focus   = 칸의 Deep + Reading 체류시간 ÷ 칸 체류시간
 * </pre>
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

    private final long[][] dwellMs = new long[DAYS][SLOTS];
    private final long[][] focusedMs = new long[DAYS][SLOTS];

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
        long dwell = AnalyticsJson.nonNegative(unit.get("dwellMs"));
        if (seenAt == null || dwell == 0) {
            return;
        }
        int day = seenAt.getDayOfWeek().getValue() - 1;
        int slot = seenAt.getHour() / SLOT_HOURS;
        dwellMs[day][slot] += dwell;
        if (FOCUSED_LABELS.contains(unit.get("clusterLabel"))) {
            focusedMs[day][slot] += dwell;
        }
    }

    private TimePatterns result() {
        long max = Arrays.stream(dwellMs).flatMapToLong(Arrays::stream).max().orElse(0);
        if (max == 0) {
            return null;
        }
        double[][] surfing = new double[DAYS][SLOTS];
        double[][] focus = new double[DAYS][SLOTS];
        for (int day = 0; day < DAYS; day++) {
            for (int slot = 0; slot < SLOTS; slot++) {
                surfing[day][slot] = ratio(dwellMs[day][slot], max);
                focus[day][slot] = ratio(focusedMs[day][slot], dwellMs[day][slot]);
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
        long[] slotDwell = new long[SLOTS];
        long[] slotFocused = new long[SLOTS];
        for (int day = 0; day < DAYS; day++) {
            for (int slot = 0; slot < SLOTS; slot++) {
                slotDwell[slot] += dwellMs[day][slot];
                slotFocused[slot] += focusedMs[day][slot];
            }
        }
        return new Note(peakSentence(slotDwell), focusSentence(slotDwell, slotFocused));
    }

    private static String peakSentence(long[] slotDwell) {
        int peak = 0;
        for (int slot = 1; slot < SLOTS; slot++) {
            if (slotDwell[slot] > slotDwell[peak]) {
                peak = slot;
            }
        }
        long total = Arrays.stream(slotDwell).sum();
        return slotDwell[peak] * 100.0 / total >= AnalyticsRules.PEAK_SLOT_PCT
                ? PEAK_SLOT.formatted(slotLabel(peak))
                : NO_PEAK_SLOT;
    }

    // 체류가 충분한 구간 중 정독 비율이 가장 높은 구간. 같으면 이른 구간, 정독이 없으면 문구 없음
    private static String focusSentence(long[] slotDwell, long[] slotFocused) {
        int best = -1;
        double bestPct = 0;
        for (int slot = 0; slot < SLOTS; slot++) {
            if (slotDwell[slot] < AnalyticsRules.MIN_FOCUS_SLOT_DWELL_MS) {
                continue;
            }
            double pct = slotFocused[slot] * 100.0 / slotDwell[slot];
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
