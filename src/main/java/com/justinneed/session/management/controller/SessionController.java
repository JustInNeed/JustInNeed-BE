package com.justinneed.session.management.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.session.management.dto.SessionDetailResponse;
import com.justinneed.session.management.dto.SessionListResponse;
import com.justinneed.session.management.dto.SessionUpdateRequest;
import com.justinneed.session.management.service.SessionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping
    public ApiResponse<List<SessionListResponse>> getSessions(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId
    ) {
        return ApiResponse.ok(sessionService.getSessions(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<SessionDetailResponse> getSession(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(sessionService.getSession(userId, id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<SessionDetailResponse> updateSession(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long id,
            @Valid @RequestBody SessionUpdateRequest request
    ) {
        return ApiResponse.ok(sessionService.updateSession(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSession(
            @RequestHeader(value = "X-User-Id", defaultValue = "1") Long userId,
            @PathVariable Long id
    ) {
        sessionService.deleteSession(userId, id);
        return ApiResponse.ok();
    }
}
