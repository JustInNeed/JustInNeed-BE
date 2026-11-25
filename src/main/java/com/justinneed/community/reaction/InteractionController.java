package com.justinneed.community.reaction;

import com.justinneed.global.common.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/community")
public class InteractionController {
    private final InteractionService service;

    public InteractionController(InteractionService service) {
        this.service = service;
    }

    @PatchMapping("/profiles/{userId}/like")
    public ApiResponse<InteractionService.LikeView> like(
            @AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long userId,
            @Valid @RequestBody LikeRequest body) {
        return ApiResponse.ok(service.like(actor, userId, body.liked()));
    }

    @Schema(name = "InteractionControllerLikeRequest")
    public record LikeRequest(@NotNull(message = "좋아요 상태를 입력해 주세요.") Boolean liked) {}
}
