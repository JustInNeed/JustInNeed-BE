package com.justinneed.community.pin;

import com.justinneed.community.dto.SharedSessionView;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
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

    @PostMapping("/me/shared-sessions")
    public ApiResponse<SharedSessionView> pin(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody PinRequest request) {
        return ApiResponse.ok(service.pin(userId, request.sessionId()));
    }

    @PatchMapping("/me/shared-sessions/order")
    public ApiResponse<List<SharedSessionView>> reorder(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody OrderRequest request) {
        return ApiResponse.ok(service.reorder(userId, request.sessionIds()));
    }

    @GetMapping("/profiles/{userId}/shared-sessions")
    public ApiResponse<List<SharedSessionView>> list(
            @PathVariable Long userId, @AuthenticationPrincipal Long viewerId) {
        return ApiResponse.ok(service.list(userId, viewerId));
    }

    @GetMapping("/profiles/{userId}/shared-sessions/{sessionId}")
    public ApiResponse<SharedSessionView> detail(@PathVariable Long userId, @PathVariable Long sessionId,
            @AuthenticationPrincipal Long viewerId) {
        return ApiResponse.ok(service.detail(userId, viewerId, sessionId));
    }

    public record PinRequest(@NotNull @Positive Long sessionId) { }
    public record OrderRequest(@NotNull List<@NotNull @Positive Long> sessionIds) { }
}
