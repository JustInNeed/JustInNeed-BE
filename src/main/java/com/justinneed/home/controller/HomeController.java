package com.justinneed.home.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.home.dto.HomeResponse;
import com.justinneed.home.service.HomeService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/home")
public class HomeController {

    private final HomeService homeService;

    public HomeController(HomeService homeService) {
        this.homeService = homeService;
    }

    @GetMapping
    public ApiResponse<HomeResponse> getHome(
            @AuthenticationPrincipal Long userId
    ) {
        return ApiResponse.ok(homeService.getHome(userId));
    }
}
