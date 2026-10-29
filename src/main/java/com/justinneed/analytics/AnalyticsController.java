package com.justinneed.analytics;
import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
@RestController @RequestMapping("/analytics")
public class AnalyticsController {
    private final AnalyticsService service;
    public AnalyticsController(AnalyticsService service) { this.service=service; }
    @GetMapping("/source-statistics")
    public ApiResponse<AnalyticsService.Sources> sources(@AuthenticationPrincipal Long user,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) { return ApiResponse.ok(service.sources(user,from,to)); }
    @GetMapping("/timeline")
    public ApiResponse<AnalyticsService.Timeline> timeline(@AuthenticationPrincipal Long user,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) { return ApiResponse.ok(service.timeline(user,from,to)); }
    @GetMapping("/time-patterns")
    public ApiResponse<AnalyticsService.TimePatterns> time(@AuthenticationPrincipal Long user,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) { return ApiResponse.ok(service.time(user,from,to)); }
    @GetMapping("/reading-behaviors")
    public ApiResponse<AnalyticsService.Reading> reading(@AuthenticationPrincipal Long user,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) { return ApiResponse.ok(service.reading(user,from,to)); }
    @GetMapping("/exploration-patterns")
    public ApiResponse<AnalyticsService.Exploration> exploration(@AuthenticationPrincipal Long user,@RequestParam(required=false) LocalDate from,@RequestParam(required=false) LocalDate to) { return ApiResponse.ok(service.exploration(user,from,to)); }
    @GetMapping("/tips")
    public ApiResponse<AnalyticsService.Tip> tips() { return ApiResponse.ok(service.tip()); }
}
