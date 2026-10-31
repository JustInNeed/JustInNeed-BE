package com.justinneed.analytics.controller;

import com.justinneed.analytics.dto.AnalyticsResponse.Categories;
import com.justinneed.analytics.dto.AnalyticsResponse.Exploration;
import com.justinneed.analytics.dto.AnalyticsResponse.ReadingBehavior;
import com.justinneed.analytics.dto.AnalyticsResponse.Recommendations;
import com.justinneed.analytics.dto.AnalyticsResponse.TimePatterns;
import com.justinneed.analytics.dto.AnalyticsResponse.WeekSessions;
import com.justinneed.analytics.service.AnalyticsService;
import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analytics")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/week-sessions")
    public ApiResponse<WeekSessions> getWeekSessions(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(analyticsService.getWeekSessions(userId));
    }

    @GetMapping("/exploration")
    public ApiResponse<Exploration> getExploration(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(analyticsService.getExploration(userId));
    }

    @GetMapping("/reading-behavior")
    public ApiResponse<ReadingBehavior> getReadingBehavior(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(analyticsService.getReadingBehavior(userId));
    }

    @GetMapping("/categories")
    public ApiResponse<Categories> getCategories(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(analyticsService.getCategories(userId));
    }

    @GetMapping("/time-patterns")
    public ApiResponse<TimePatterns> getTimePatterns(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(analyticsService.getTimePatterns(userId));
    }

    @GetMapping("/recommendations")
    public ApiResponse<Recommendations> getRecommendations(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(analyticsService.getRecommendations(userId));
    }
}
