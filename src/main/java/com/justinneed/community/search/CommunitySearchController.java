package com.justinneed.community.search;

import com.justinneed.global.common.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/search")
public class CommunitySearchController {
    private final CommunitySearchService service;
    public CommunitySearchController(CommunitySearchService service) { this.service = service; }
    @GetMapping("/profiles")
    public ApiResponse<CommunitySearchService.Profiles> profiles(@RequestParam String hashtag,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.profiles(hashtag, page, size));
    }
    @GetMapping("/mindmaps")
    public ApiResponse<CommunitySearchService.Mindmaps> mindmaps(@RequestParam String hashtag,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.mindmaps(hashtag, page, size));
    }
}
