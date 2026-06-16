package com.justinneed.auth.controller;

import com.justinneed.auth.dto.LogoutRequest;
import com.justinneed.auth.dto.TokenRefreshRequest;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.jwt.TokenDenylist;
import com.justinneed.global.common.ApiResponse;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenDenylist tokenDenylist;

    public AuthController(JwtTokenProvider jwtTokenProvider, TokenDenylist tokenDenylist) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenDenylist = tokenDenylist;
    }

    // refresh 토큰으로 access(+refresh) 재발급. (러프 단계: refresh 토큰을 DB 저장 없이 무상태 검증)
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody TokenRefreshRequest request) {
        String refreshToken = request.refreshToken();
        if (refreshToken == null
                || !jwtTokenProvider.validate(refreshToken)
                || !jwtTokenProvider.isRefreshToken(refreshToken)
                || tokenDenylist.isDenied(refreshToken)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        Long memberId = jwtTokenProvider.getMemberId(refreshToken);
        return ApiResponse.ok(new TokenResponse(
                jwtTokenProvider.createAccessToken(memberId),
                jwtTokenProvider.createRefreshToken(memberId)
        ));
    }

    // 로그아웃: 제시된 access/refresh 토큰을 denylist 에 등록해 즉시 무효화. (멱등 — 토큰이 없거나 이미 만료여도 200)
    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) LogoutRequest request
    ) {
        denyIfValid(resolveBearer(authorization));
        if (request != null) {
            denyIfValid(request.refreshToken());
        }
        return ApiResponse.ok();
    }

    private void denyIfValid(String token) {
        if (token != null && jwtTokenProvider.validate(token)) {
            tokenDenylist.deny(token, jwtTokenProvider.getExpiryEpochSecond(token));
        }
    }

    private String resolveBearer(String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return null;
    }
}
