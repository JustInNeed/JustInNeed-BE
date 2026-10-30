package com.justinneed.auth.jwt;

import com.justinneed.global.common.ApiResponse;
import com.justinneed.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 보호 API 를 인증 없이 부를 때(토큰 없음 · 잘못됨 · 만료 · 무효화 · refresh 토큰 · 탈퇴 회원)의 401 응답.
 * 시큐리티 필터 단계라 GlobalExceptionHandler 를 거치지 않으므로 같은 {success:false, data:null, message} 형식을 여기서 쓴다.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JwtAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        ErrorCode errorCode = ErrorCode.LOGIN_EXPIRED;
        response.setStatus(errorCode.getStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // JSON 응답에도 charset=UTF-8 을 붙인다 (없으면 Safari 가 로케일 인코딩으로 추측해 한글이 깨짐)
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(errorCode.getMessage())));
    }
}
