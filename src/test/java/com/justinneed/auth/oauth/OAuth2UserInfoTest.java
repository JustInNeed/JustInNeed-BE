package com.justinneed.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.justinneed.auth.domain.SocialProvider;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;

/**
 * 카카오(/v2/user/me) · 네이버(/v1/nid/me) 프로필 응답에서 providerId · email 을 꺼내는 규칙.
 */
@DisplayName("소셜 프로필 응답 파싱")
class OAuth2UserInfoTest {

    @Test
    @DisplayName("카카오: 숫자 id 를 문자열 providerId 로, kakao_account.email 을 이메일로 꺼낸다")
    void kakaoWithEmail() {
        Map<String, Object> attributes = Map.of(
                "id", 4123456789L,
                "kakao_account", Map.of("email", "sprout@kakao.com", "profile", Map.of("nickname", "새싹")));

        OAuth2UserInfo info = OAuth2UserInfo.of(SocialProvider.KAKAO, attributes);

        assertThat(info.getProviderId()).isEqualTo("4123456789");
        assertThat(info.getEmail()).isEqualTo("sprout@kakao.com");
    }

    @Test
    @DisplayName("카카오: 이메일 동의를 안 했거나(kakao_account 에 email 없음) kakao_account 자체가 없으면 이메일은 null")
    void kakaoWithoutEmail() {
        OAuth2UserInfo noEmail = OAuth2UserInfo.of(SocialProvider.KAKAO,
                Map.of("id", 4123456789L, "kakao_account", Map.of("profile_needs_agreement", true)));
        OAuth2UserInfo noAccount = OAuth2UserInfo.of(SocialProvider.KAKAO, Map.of("id", 4123456789L));

        assertThat(noEmail.getProviderId()).isEqualTo("4123456789");
        assertThat(noEmail.getEmail()).isNull();
        assertThat(noAccount.getProviderId()).isEqualTo("4123456789");
        assertThat(noAccount.getEmail()).isNull();
    }

    @Test
    @DisplayName("네이버: response 안의 id · email 을 꺼낸다")
    void naverWithEmail() {
        Map<String, Object> attributes = Map.of(
                "resultcode", "00",
                "message", "success",
                "response", Map.of("id", "naver-abc123", "email", "sprout@naver.com", "name", "김새싹"));

        OAuth2UserInfo info = OAuth2UserInfo.of(SocialProvider.NAVER, attributes);

        assertThat(info.getProviderId()).isEqualTo("naver-abc123");
        assertThat(info.getEmail()).isEqualTo("sprout@naver.com");
    }

    @Test
    @DisplayName("네이버: response 에 email 이 없으면 이메일은 null")
    void naverWithoutEmail() {
        OAuth2UserInfo info = OAuth2UserInfo.of(SocialProvider.NAVER,
                Map.of("resultcode", "00", "response", Map.of("id", "naver-abc123")));

        assertThat(info.getProviderId()).isEqualTo("naver-abc123");
        assertThat(info.getEmail()).isNull();
    }

    @Test
    @DisplayName("네이버: response 가 없거나 객체가 아니면 invalid_user_info 로 로그인 실패 처리한다")
    void malformedNaverResponse() {
        Map<String, Object> notObject = new HashMap<>();
        notObject.put("resultcode", "024");
        notObject.put("response", "Authentication failed");

        assertThatThrownBy(() -> OAuth2UserInfo.of(SocialProvider.NAVER, Map.of("resultcode", "024")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(error -> ((OAuth2AuthenticationException) error).getError().getErrorCode())
                .isEqualTo("invalid_user_info");
        assertThatThrownBy(() -> OAuth2UserInfo.of(SocialProvider.NAVER, notObject))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(error -> ((OAuth2AuthenticationException) error).getError().getErrorCode())
                .isEqualTo("invalid_user_info");
    }

    @Test
    @DisplayName("구글은 아직 지원하지 않아 unsupported_provider 로 로그인 실패 처리한다")
    void unsupportedProvider() {
        assertThatThrownBy(() -> OAuth2UserInfo.of(SocialProvider.GOOGLE, Map.of("sub", "google-1")))
                .isInstanceOf(OAuth2AuthenticationException.class)
                .extracting(error -> ((OAuth2AuthenticationException) error).getError().getErrorCode())
                .isEqualTo("unsupported_provider");
    }
}
