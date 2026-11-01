package com.justinneed.community.pin;

import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community")
public class SharedSessionController {
    private final SharedSessionService service;

    public SharedSessionController(SharedSessionService service) {
        this.service = service;
    }

    @GetMapping("/profiles/{userId}/shared-sessions")
    public ApiResponse<SharedSessionService.SharedSessions> list(
            @PathVariable Long userId, @AuthenticationPrincipal Long viewerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size) {
        return ApiResponse.ok(service.list(userId, viewerId, page, size));
    }

    @GetMapping("/profiles/{userId}/shared-sessions/{sessionId}")
    public ApiResponse<SharedSessionView> detail(@PathVariable Long userId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Long viewerId) {
        return ApiResponse.ok(service.detail(userId, viewerId, sessionId));
    }

    public record PinRequest(@NotNull(message = "세션을 선택해 주세요.") @Positive(message = "세션이 올바르지 않습니다.") Long sessionId) { }
    public record OrderRequest(@NotNull(message = "정렬할 세션 목록을 입력해 주세요.")
            List<@NotNull(message = "세션이 올바르지 않습니다.") @Positive(message = "세션이 올바르지 않습니다.") Long> sessionIds) { }
}
