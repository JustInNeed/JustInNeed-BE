package com.justinneed.community.interestdelete;

import com.justinneed.global.common.ApiResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/me/interests")
public class InterestDeleteController {
    private final InterestDeleteService service;

    public InterestDeleteController(InterestDeleteService service) {
        this.service = service;
    }

    @DeleteMapping("/{hashtag}")
    public ApiResponse<List<String>> delete(
            @AuthenticationPrincipal Long userId, @PathVariable String hashtag) {
        return ApiResponse.ok(service.delete(userId, hashtag));
    }
}
