package com.justinneed.auth.controller;

import com.justinneed.auth.dto.TokenRefreshRequest;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.global.common.ApiResponse;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    // refresh 토큰으로 access(+refresh) 재발급. (러프 단계: refresh 토큰을 DB 저장 없이 무상태 검증)
    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody TokenRefreshRequest request) {
        String refreshToken = request.refreshToken();
        if (refreshToken == null || !jwtTokenProvider.validate(refreshToken) || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        Long memberId = jwtTokenProvider.getMemberId(refreshToken);
        return ApiResponse.ok(new TokenResponse(
                jwtTokenProvider.createAccessToken(memberId),
                jwtTokenProvider.createRefreshToken(memberId)
        ));
    }
}
