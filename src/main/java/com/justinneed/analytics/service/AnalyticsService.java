package com.justinneed.analytics.service;

import com.justinneed.JustInNeedApplication;
import com.justinneed.analytics.ReadingDistribution;
import com.justinneed.analytics.dto.AnalyticsResponse.Categories;
import com.justinneed.analytics.dto.AnalyticsResponse.Exploration;
import com.justinneed.analytics.dto.AnalyticsResponse.ReadingBehavior;
import com.justinneed.analytics.dto.AnalyticsResponse.Recommendations;
import com.justinneed.analytics.dto.AnalyticsResponse.TimePatterns;
import com.justinneed.analytics.dto.AnalyticsResponse.WeekSessions;
import com.justinneed.analytics.repository.AnalyticsSessionRepository;
import com.justinneed.analytics.repository.AnalyticsSessionRow;
import com.justinneed.analytics.repository.AnalyticsUnitsRow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분석 화면 카드별 집계. 대상은 내 완료(COMPLETED) · 미삭제 세션이고, 기간은 오늘 포함 최근 7일(세션 시작 시각 KST)이다.
 * 카드마다 보여줄 데이터가 없으면 null 을 준다 (이번 주 세션 · 추천 행동은 빈 목록).
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final ZoneId KST = ZoneId.of(JustInNeedApplication.TIME_ZONE);
    private static final int WEEK_DAYS = 7;

    private final AnalyticsSessionRepository repository;
    private final Clock clock;

    public AnalyticsService(AnalyticsSessionRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public WeekSessions getWeekSessions(Long userId) {
        return new WeekSessions(repository.findWeekSessions(userId, weekStart(), tomorrow()));
    }

    public Exploration getExploration(Long userId) {
        return ExplorationAggregator.aggregate(sessions(userId));
    }

    public ReadingBehavior getReadingBehavior(Long userId) {
        List<Map<String, Object>> summaries = sessions(userId).stream()
                .map(AnalyticsSessionRow::analysisSummary)
                .toList();
        ReadingDistribution distribution = ReadingDistribution.ofSummaries(summaries);
        return distribution.hasData() ? AnalyticsNotes.readingBehavior(distribution) : null;
    }

    public Categories getCategories(Long userId) {
        return VisitAggregator.of(sessions(userId)).categories();
    }

    public Recommendations getRecommendations(Long userId) {
        return new Recommendations(VisitAggregator.of(sessions(userId)).recommendations());
    }

    public TimePatterns getTimePatterns(Long userId) {
        List<AnalyticsUnitsRow> units = repository.findUnits(userId, weekStart(), tomorrow());
        return TimePatternAggregator.aggregate(units);
    }

    private List<AnalyticsSessionRow> sessions(Long userId) {
        return repository.findSessions(userId, weekStart(), tomorrow());
    }

    private LocalDateTime weekStart() {
        return today().minusDays(WEEK_DAYS - 1).atStartOfDay();
    }

    private LocalDateTime tomorrow() {
        return today().plusDays(1).atStartOfDay();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), KST);
    }
}
