package com.justinneed.community.interest;

import com.justinneed.global.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community")
public class InterestController {
    private final InterestService service;

    public InterestController(InterestService service) {
        this.service = service;
    }

    @GetMapping("/me/interests")
    public ApiResponse<List<String>> mine(@AuthenticationPrincipal Long userId) {
        return ApiResponse.ok(service.get(userId, userId));
    }

    @GetMapping("/profiles/{userId}/interests")
    public ApiResponse<List<String>> get(@PathVariable Long userId, @AuthenticationPrincipal Long viewerId) {
        return ApiResponse.ok(service.get(userId, viewerId));
    }

    @PostMapping("/me/interests")
    public ApiResponse<List<String>> add(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody Request request) {
        return ApiResponse.ok(service.add(userId, request.hashtag()));
    }

    public record Request(
            @NotBlank
            @Pattern(regexp = "^[가-힣a-zA-Z0-9]{1,10}$",
                    message = "해시태그는 한글, 영문, 숫자만 사용할 수 있으며 최대 10자까지 입력 가능합니다.")
            String hashtag
    ) { }
}
