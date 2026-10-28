package com.justinneed.community.bookmark;

import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/me/bookmarks")
public class BookmarkQueryController {
    private final BookmarkQueryService service;
    public BookmarkQueryController(BookmarkQueryService service) { this.service = service; }

    @GetMapping("/hashtag-bundles")
    public ApiResponse<BookmarkQueryService.Bundles> bundles(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.bundles(userId, page, size));
    }
    @GetMapping("/mindmaps")
    public ApiResponse<BookmarkQueryService.Mindmaps> mindmaps(@AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.mindmaps(userId, page, size));
    }
}
