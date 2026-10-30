package com.justinneed.auth.oauth;

import com.justinneed.auth.service.LoginCodeService;
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
 * 소셜 로그인 성공 시 일회용 로그인 코드를 발급하고, 코드만 쿼리스트링에 담아 FE 콜백으로 리다이렉트한다.
 * 토큰은 URL 에 싣지 않는다 (FE 호스팅 접근 로그 · 브라우저 기록에 남음). FE 가 POST /auth/token 으로 코드를 토큰과 바꾼다.
 */
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LoginCodeService loginCodeService;
    private final String successRedirectUri;

    public OAuth2SuccessHandler(
            LoginCodeService loginCodeService,
            @Value("${app.oauth.success-redirect-uri}") String successRedirectUri
    ) {
        this.loginCodeService = loginCodeService;
        this.successRedirectUri = successRedirectUri;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        OAuth2User principal = (OAuth2User) authentication.getPrincipal();
        Long memberId = ((Number) principal.getAttributes().get(CustomOAuth2UserService.ATTR_MEMBER_ID)).longValue();
        boolean needsNickname = Boolean.TRUE.equals(principal.getAttributes().get(CustomOAuth2UserService.ATTR_NEEDS_NICKNAME));

        String code = loginCodeService.issue(memberId, needsNickname);

        String targetUrl = UriComponentsBuilder.fromUriString(successRedirectUri)
                .queryParam("code", code)
                .build()
                .toUriString();

        getRedirectStrategy().sendRedirect(request, response, targetUrl);
    }
}
