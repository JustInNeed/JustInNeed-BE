package com.justinneed.auth.controller;

import com.justinneed.auth.dto.LogoutRequest;
import com.justinneed.auth.dto.TokenRefreshRequest;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.service.AuthTokenService;
import com.justinneed.global.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final AuthTokenService authTokenService;

    public AuthController(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    // refresh 토큰으로 access(+refresh) 재발급. (DB 화이트리스트 확인 + 회전)
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody TokenRefreshRequest request) {
        return ApiResponse.ok(authTokenService.reissue(request.refreshToken()));
    }

    // 로그아웃: refresh 화이트리스트에서 삭제 + access 즉시 무효화. (멱등 — 토큰이 없거나 만료여도 200)
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) LogoutRequest request
    ) {
        String accessToken = resolveBearer(authorization);
        String refreshToken = request == null ? null : request.refreshToken();
        authTokenService.logout(accessToken, refreshToken);
        return ApiResponse.ok();
    }

    private String resolveBearer(String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
