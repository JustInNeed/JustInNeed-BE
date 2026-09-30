package com.justinneed.community.edit;

import com.justinneed.community.pin.SharedSessionController;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community")
public class ProfileEditController {
    private final ProfileEditService service;
    public ProfileEditController(ProfileEditService service) { this.service = service; }

    @PatchMapping("/me/hashtags/{hashtagId}/visibility")
    public ApiResponse<ProfileEditService.TagView> visibility(@AuthenticationPrincipal Long userId,
            @PathVariable Long hashtagId, @Valid @RequestBody PublicRequest request) {
        return ApiResponse.ok(service.visibility(userId, hashtagId, request.isPublic()));
    }
    @PostMapping("/me/pinned-sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProfileEditService.PinView> pin(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody SharedSessionController.PinRequest request) {
        return ApiResponse.ok(service.pin(userId, request.sessionId()));
    }
    @DeleteMapping("/me/pinned-sessions/{sessionId}")
    public ApiResponse<ProfileEditService.PinView> unpin(@AuthenticationPrincipal Long userId, @PathVariable Long sessionId) {
        return ApiResponse.ok(service.unpin(userId, sessionId));
    }
    @PatchMapping("/me/pinned-sessions/order")
    public ApiResponse<ProfileEditService.OrderView> order(@AuthenticationPrincipal Long userId,
            @Valid @RequestBody SharedSessionController.OrderRequest request) {
        return ApiResponse.ok(service.reorder(userId, request.sessionIds()));
    }
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<ProfileEditService.SessionView> session(@AuthenticationPrincipal Long viewerId, @PathVariable Long sessionId) {
        return ApiResponse.ok(service.session(viewerId, sessionId));
    }
    public record PublicRequest(@NotNull Boolean isPublic) { }
}
