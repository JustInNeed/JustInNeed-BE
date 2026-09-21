package com.justinneed.community.visibility;

import com.justinneed.community.domain.NodeVisibility;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community")
public class VisibilityController {
    private final VisibilityService service;

    public VisibilityController(VisibilityService service) {
        this.service = service;
    }

    @GetMapping("/me/visibility")
    public ApiResponse<VisibilityService.VisibilityResponse> get(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.get(userId));
    }

    @PatchMapping("/me/visibility")
    public ApiResponse<VisibilityService.VisibilityResponse> update(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody VisibilityRequest request) {
        return ApiResponse.ok(service.update(userId, request.visibility()));
    }

    @GetMapping("/profiles/{userId}/nodes")
    public ApiResponse<List<VisibilityService.NodeResponse>> nodes(
            @PathVariable Long userId, @AuthenticationPrincipal Long viewerId) {
        return ApiResponse.ok(service.nodes(userId, viewerId));
    }

    public record VisibilityRequest(@NotNull NodeVisibility visibility) { }
}
