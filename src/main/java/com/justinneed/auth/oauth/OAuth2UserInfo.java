package com.justinneed.auth.oauth;

import com.justinneed.auth.domain.SocialProvider;
import java.util.Map;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

/**
 * 카카오/네이버가 내려주는 프로필 JSON 구조가 달라서, provider별로 providerId/email을 추출한다.
 */
public class OAuth2UserInfo {

    private final String providerId;
    private final String email;

    private OAuth2UserInfo(String providerId, String email) {
        this.providerId = providerId;
        this.email = email;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getEmail() {
        return email;
    }

    public static OAuth2UserInfo of(SocialProvider provider, Map<String, Object> attributes) {
        return switch (provider) {
            case KAKAO -> ofKakao(attributes);
            case NAVER -> ofNaver(attributes);
            default -> throw new OAuth2AuthenticationException(
                    new OAuth2Error("unsupported_provider"), "지원하지 않는 소셜 로그인입니다: " + provider);
        };
    }

    // kakao: { id, kakao_account: { email, profile: { nickname } }, ... }
    @SuppressWarnings("unchecked")
    private static OAuth2UserInfo ofKakao(Map<String, Object> attributes) {
        String providerId = String.valueOf(attributes.get("id"));
        String email = null;
        Object account = attributes.get("kakao_account");
        if (account instanceof Map<?, ?> kakaoAccount) {
            Object emailValue = ((Map<String, Object>) kakaoAccount).get("email");
            email = emailValue == null ? null : String.valueOf(emailValue);
        }
        return new OAuth2UserInfo(providerId, email);
    }

    // naver: { response: { id, email, nickname, name } }
    @SuppressWarnings("unchecked")
    private static OAuth2UserInfo ofNaver(Map<String, Object> attributes) {
        Object response = attributes.get("response");
        if (!(response instanceof Map<?, ?> naverResponse)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"),
                    "네이버 응답 형식이 올바르지 않습니다.");
        }
        Map<String, Object> map = (Map<String, Object>) naverResponse;
        String providerId = String.valueOf(map.get("id"));
        Object emailValue = map.get("email");
        String email = emailValue == null ? null : String.valueOf(emailValue);
        return new OAuth2UserInfo(providerId, email);
    }
}
