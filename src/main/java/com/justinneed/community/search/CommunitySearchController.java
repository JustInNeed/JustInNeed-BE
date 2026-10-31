package com.justinneed.community.search;

import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/search")
public class CommunitySearchController {
    private final CommunitySearchService service;
    public CommunitySearchController(CommunitySearchService service) { this.service = service; }
    @GetMapping("/profiles")
    public ApiResponse<CommunitySearchService.Profiles> profiles(@RequestParam String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.profiles(q, page, size));
    }
    @GetMapping("/mindmaps")
    public ApiResponse<CommunitySearchService.Mindmaps> mindmaps(@AuthenticationPrincipal Long actor, @RequestParam String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.mindmaps(actor, q, page, size));
    }
    @GetMapping("/hashtags")
    public ApiResponse<CommunitySearchService.Hashtags> hashtags(@RequestParam String q,
            @RequestParam(defaultValue = "5") int limit) {
        return ApiResponse.ok(service.hashtags(q, limit));
    }
}
