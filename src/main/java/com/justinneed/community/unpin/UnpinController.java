package com.justinneed.community.unpin;

import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/me/shared-sessions")
public class UnpinController {
    private final UnpinService service;

    public UnpinController(UnpinService service) {
        this.service = service;
    }

    @DeleteMapping("/{sessionId}")
    public ApiResponse<Void> unpin(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
        service.unpin(userId, sessionId);
        return ApiResponse.ok();
    }
}
