package com.justinneed.session.management.controller;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.session.management.dto.SessionDetailResponse;
import com.justinneed.session.management.dto.SessionPageResponse;
import com.justinneed.session.management.dto.SessionUpdateRequest;
import com.justinneed.session.management.service.SessionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sessions")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    // 무한 스크롤 (REC-10-1): page 0 부터 size 개씩. favorite=true 면 즐겨찾기만 (REC-10-3)
    @GetMapping
    public ApiResponse<SessionPageResponse> getSessions(
            @AuthenticationPrincipal Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "false") boolean favorite
    ) {
        return ApiResponse.ok(sessionService.getSessions(userId, page, size, favorite));
    }

    // 그룹 만들기 해시태그 후보 (목록을 페이지로 받아도 전체 세션 기준). /{id} 보다 이 고정 경로가 먼저 매칭된다
    @GetMapping("/hashtags")
    public ApiResponse<List<String>> getHashtags(
            @AuthenticationPrincipal Long userId
    ) {
        return ApiResponse.ok(sessionService.getHashtags(userId));
    }

    @GetMapping("/{id}")
    public ApiResponse<SessionDetailResponse> getSession(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id
    ) {
        return ApiResponse.ok(sessionService.getSession(userId, id));
    }

    @PatchMapping("/{id}")
    public ApiResponse<SessionDetailResponse> updateSession(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @Valid @RequestBody SessionUpdateRequest request
    ) {
        return ApiResponse.ok(sessionService.updateSession(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteSession(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id
    ) {
        sessionService.deleteSession(userId, id);
        return ApiResponse.ok();
    }
}
