package com.justinneed.community.reaction;

import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/mindmaps")
public class CardReactionController {
    private final CardReactionService service;
    public CardReactionController(CardReactionService service) { this.service = service; }
    @PatchMapping("/{mindmapId}/like")
    public ApiResponse<CardReactionService.LikeView> like(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long mindmapId, @Valid @RequestBody LikeRequest body) {
        return ApiResponse.ok(service.like(actor, mindmapId, body.liked()));
    }
    @PatchMapping("/{mindmapId}/exploring")
    public ApiResponse<CardReactionService.ExploringView> exploring(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long mindmapId, @Valid @RequestBody ExploringRequest body) {
        return ApiResponse.ok(service.exploring(actor, mindmapId, body.exploring()));
    }
    @PatchMapping("/{mindmapId}/bookmark")
    public ApiResponse<CardReactionService.BookmarkView> bookmark(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long mindmapId, @Valid @RequestBody BookmarkRequest body) {
        return ApiResponse.ok(service.bookmark(actor, mindmapId, body.bookmarked()));
    }
    public record LikeRequest(@NotNull(message = "좋아요 상태를 입력해 주세요.") Boolean liked) { }
    public record ExploringRequest(@NotNull(message = "나도 탐색중 상태를 입력해 주세요.") Boolean exploring) { }
    public record BookmarkRequest(@NotNull(message = "북마크 상태를 입력해 주세요.") Boolean bookmarked) { }
}
