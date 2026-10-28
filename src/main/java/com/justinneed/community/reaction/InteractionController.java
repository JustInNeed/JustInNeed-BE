package com.justinneed.community.reaction;

import com.justinneed.community.bookmark.TargetType;
import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community")
public class InteractionController {
    private final InteractionService service;
    public InteractionController(InteractionService service) { this.service = service; }
    @PatchMapping("/profiles/{userId}/like")
    public ApiResponse<InteractionService.LikeView> like(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long userId, @Valid @RequestBody LikeRequest body) {
        return ApiResponse.ok(service.like(actor, userId, body.liked()));
    }
    @PatchMapping("/exploring")
    public ApiResponse<InteractionService.ExploringView> exploring(@AuthenticationPrincipal Long actor,
            @Valid @RequestBody ExploringRequest body) {
        return ApiResponse.ok(service.exploring(actor, body.targetType(), body.targetId(), body.exploring()));
    }
    @GetMapping("/exploring")
    public ApiResponse<InteractionService.ExploringView> status(@AuthenticationPrincipal Long actor,
            @RequestParam TargetType targetType, @RequestParam @Positive Long targetId) {
        return ApiResponse.ok(service.status(actor, targetType, targetId));
    }
    public record LikeRequest(@NotNull Boolean liked) { }
    public record ExploringRequest(@NotNull TargetType targetType, @NotNull @Positive Long targetId,
            @NotNull Boolean exploring) { }
}
