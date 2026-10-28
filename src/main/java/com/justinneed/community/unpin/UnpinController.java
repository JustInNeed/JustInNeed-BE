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

    // 중복 API: 핀고정 해제는 DELETE /community/me/pinned-sessions/{sessionId} 사용 (2026-10-29 주석처리)
    // @DeleteMapping("/{sessionId}")
    // public ApiResponse<Void> unpin(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
    //     service.unpin(userId, sessionId);
    //     return ApiResponse.ok();
    // }
}
