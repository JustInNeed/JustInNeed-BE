package com.justinneed.community.card;

import com.justinneed.global.common.ApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController
@RequestMapping("/community")
public class CardSessionsController {
    private final CardSessionsService service;

    public CardSessionsController(CardSessionsService service) {
        this.service = service;
    }

    @GetMapping("/mindmaps/{id}/sessions")
    public ApiResponse<CardSessionsService.Sessions> sessions(@PathVariable Long id) {
        return ApiResponse.ok(service.related(id));
    }

    @GetMapping("/profiles/{userId}/mindmaps")
    public ApiResponse<CardSessionsService.Mindmaps> mindmaps(@PathVariable Long userId,
            @AuthenticationPrincipal Long viewerId,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.ok(service.ofOwner(userId, viewerId, page, size));
    }
}
