package com.justinneed.community.hashtag;

import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/me/hashtags")
public class HashtagVisibilityController {
    private final HashtagVisibilityService service;

    public HashtagVisibilityController(HashtagVisibilityService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<HashtagVisibilityService.HashtagResponse>> get(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.get(userId));
    }

    @PatchMapping("/visibility")
    public ApiResponse<HashtagVisibilityService.HashtagResponse> update(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody Request request) {
        return ApiResponse.ok(service.update(userId, request.hashtag(), request.isPublic()));
    }

    public record Request(@NotBlank String hashtag, @NotNull Boolean isPublic) { }
}
