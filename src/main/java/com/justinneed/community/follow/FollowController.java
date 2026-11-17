package com.justinneed.community.follow;

import com.justinneed.global.common.ApiResponse;
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
@RequestMapping("/community/profiles")
public class FollowController {
    private final FollowService follows;
    public FollowController(FollowService follows) { this.follows = follows; }
    @PatchMapping("/{userId}/follow")
    public ApiResponse<FollowService.FollowView> follow(@AuthenticationPrincipal Long actor,
            @PathVariable @Positive Long userId, @Valid @RequestBody FollowRequest body) {
        return ApiResponse.ok(follows.follow(actor, userId, body.following()));
    }
    public record FollowRequest(@NotNull(message = "팔로우 여부를 입력해 주세요.") Boolean following) { }
}
