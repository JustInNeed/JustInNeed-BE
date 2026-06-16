package com.justinneed.auth.oauth;

import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.service.AuthTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 성공 시 access/refresh 토큰을 발급하고, 토큰을 쿼리스트링에 담아 FE 콜백으로 리다이렉트한다.
 * (쿠키는 추후 도입 예정 — 지금은 러프하게 쿼리스트링으로 전달)
 */
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthTokenService authTokenService;
    private final String successRedirectUri;

    public OAuth2SuccessHandler(
            AuthTokenService authTokenService,
            @Value("${app.oauth.success-redirect-uri}") String successRedirectUri
    ) {
        this.authTokenService = authTokenService;
        this.successRedirectUri = successRedirectUri;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        Long memberId = ((Number) principal.getAttributes().get(CustomOAuth2UserService.ATTR_MEMBER_ID)).longValue();
        boolean needsNickname = Boolean.TRUE.equals(principal.getAttributes().get(CustomOAuth2UserService.ATTR_NEEDS_NICKNAME));

        TokenResponse tokens = authTokenService.issue(memberId);

        String targetUrl = UriComponentsBuilder.fromUriString(successRedirectUri)
                .queryParam("accessToken", tokens.accessToken())
                .queryParam("refreshToken", tokens.refreshToken())
                .queryParam("needsNickname", needsNickname)
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
