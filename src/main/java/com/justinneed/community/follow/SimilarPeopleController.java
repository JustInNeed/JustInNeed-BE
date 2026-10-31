package com.justinneed.community.follow;

import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/community/recommendations/profiles")
public class SimilarPeopleController {
    private final SimilarPeopleService similarPeople;
    public SimilarPeopleController(SimilarPeopleService similarPeople) { this.similarPeople = similarPeople; }
    @GetMapping("/similar")
    public ApiResponse<SimilarPeopleService.SimilarPeople> similar(@AuthenticationPrincipal Long actor,
            @RequestParam(defaultValue = "3") int limit) {
        return ApiResponse.ok(similarPeople.similar(actor, limit));
    }
}
