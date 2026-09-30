package com.justinneed.community.recommend;

import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/recommendations")
public class RecommendationController {
    private final RecommendationService recommendations;
    private final TrendingService trending;
    public RecommendationController(RecommendationService recommendations, TrendingService trending) {
        this.recommendations = recommendations; this.trending = trending;
    }
    @GetMapping("/mindmaps/interests")
    public ApiResponse<RecommendationService.Recommendations> interests(@AuthenticationPrincipal Long actor,
            @RequestParam(defaultValue = "3") int limit) {
        return ApiResponse.ok(recommendations.interests(actor, limit));
    }
    @GetMapping("/mindmaps/recent-bookmarks")
    public ApiResponse<RecommendationService.Recommendations> recent(@AuthenticationPrincipal Long actor,
            @RequestParam(defaultValue = "3") int limit) {
        return ApiResponse.ok(recommendations.recent(actor, limit));
    }
    @GetMapping("/hashtags/trending")
    public ApiResponse<TrendingService.Trends> trending(@RequestParam(defaultValue = "TODAY") TrendingService.Period period,
            @RequestParam(defaultValue = "10") int limit) {
        return ApiResponse.ok(trending.trending(period, limit));
    }
}
