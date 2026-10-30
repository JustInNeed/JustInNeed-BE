package com.justinneed.auth.dto;

// 소셜 로그인 콜백 URL(?code=...)로 받은 일회용 로그인 코드
public record TokenExchangeRequest(
        String code
) {
}
