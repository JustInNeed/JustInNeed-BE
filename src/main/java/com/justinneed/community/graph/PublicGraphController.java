package com.justinneed.community.graph;

import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community")
public class PublicGraphController {
    private final PublicGraphService service;
    public PublicGraphController(PublicGraphService service) { this.service = service; }
    @GetMapping("/profiles/{userId}")
    public ApiResponse<PublicGraphService.Profile> profile(@PathVariable Long userId, @AuthenticationPrincipal Long viewerId) {
        return ApiResponse.ok(service.profile(userId, viewerId));
    }
    @GetMapping("/profiles/{userId}/mindmap")
    public ApiResponse<PublicGraphService.Graph> graph(@PathVariable Long userId, @RequestParam(required = false) Long hashtagId) {
        return ApiResponse.ok(service.graph(userId, hashtagId));
    }
    @GetMapping("/me/bookmarks/{bookmarkId}/original")
    public ApiResponse<PublicGraphService.Original> original(@AuthenticationPrincipal Long viewerId, @PathVariable Long bookmarkId) {
        return ApiResponse.ok(service.original(viewerId, bookmarkId));
    }
}
