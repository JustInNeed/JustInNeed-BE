package com.justinneed.community.me;

import com.justinneed.community.card.CardSessionsService;
import com.justinneed.community.graph.PublicGraphService;
import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/me")
public class MyProfileController {
    private final MyProfileService service;
    public MyProfileController(MyProfileService service) { this.service = service; }

    @GetMapping("/profile")
    public ApiResponse<MyProfileService.MyProfile> profile(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.profile(userId));
    }
    @GetMapping("/sessions")
    public ApiResponse<MyProfileService.MySessions> sessions(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "false") boolean publicOnly) {
        return ApiResponse.ok(service.sessions(userId, page, size, publicOnly));
    }
    @GetMapping("/mindmaps")
    public ApiResponse<MyProfileService.MyCards> mindmaps(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.ok(service.mindmaps(userId, page, size));
    }
    @GetMapping("/mindmaps/{id}/sessions")
    public ApiResponse<CardSessionsService.Sessions> related(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return ApiResponse.ok(service.related(userId, id));
    }
    @GetMapping("/mindmap")
    public ApiResponse<PublicGraphService.Graph> mindmap(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.mindmap(userId));
    }
}
