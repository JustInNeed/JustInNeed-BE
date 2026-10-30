package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.oauth.CustomOAuth2UserService;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.core.user.OAuth2User;

/**
 * 소셜 로그인 (/login → 카카오 · 네이버 → /oauth/callback).
 * 프로바이더 호출(토큰 · 프로필 조회)은 건너뛰고, 프로필 응답 JSON 으로 회원 조회 · 생성 단계부터 확인한다.
 */
@DisplayName("소셜 로그인")
class LoginScenarioTest extends ScenarioTestSupport {

    private static final String FAILURE_REDIRECT = "http://localhost:3000/login?error=oauth";

    @Autowired
    private CustomOAuth2UserService oAuth2UserService;

    private static Map<String, Object> kakaoProfile(long id, String email) {
        Map<String, Object> account = new HashMap<>();
        if (email != null) {
            account.put("email", email);
        }
        return Map.of("id", id, "kakao_account", account);
    }

    private static Map<String, Object> naverProfile(String id, String email) {
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        if (email != null) {
            response.put("email", email);
        }
        return Map.of("resultcode", "00", "message", "success", "response", response);
    }

    /** 프로바이더가 프로필을 돌려준 뒤의 로그인 처리 (CustomOAuth2UserService#loadUser 의 회원 조회 · 생성 단계). */
    private OAuth2User login(String registrationId, Map<String, Object> profile) {
        return oAuth2UserService.resolve(registrationId, profile);
    }

    private static Long memberIdOf(OAuth2User principal) {
        return ((Number) principal.getAttributes().get(CustomOAuth2UserService.ATTR_MEMBER_ID)).longValue();
    }

    private static boolean needsNickname(OAuth2User principal) {
        return Boolean.TRUE.equals(principal.getAttributes().get(CustomOAuth2UserService.ATTR_NEEDS_NICKNAME));
    }

    @Test
    @DisplayName("카카오로 처음 로그인하면 닉네임 없는 회원이 생기고 needsNickname=true 라 FE 가 닉네임 입력 화면으로 보낸다")
    void firstKakaoLoginCreatesMember() {
        OAuth2User principal = login("kakao", kakaoProfile(4100000001L, "sprout@kakao.com"));

        Member created = members.findById(memberIdOf(principal)).orElseThrow();
        assertThat(created.getSocialProvider()).isEqualTo(SocialProvider.KAKAO);
        assertThat(created.getProviderId()).isEqualTo("4100000001");
        assertThat(created.getNickname()).isNull();
        assertThat(created.getEmail()).isEqualTo("sprout@kakao.com");
        assertThat(created.isWithdrawn()).isFalse();
        assertThat(needsNickname(principal)).isTrue();
        assertThat(principal.getName()).isEqualTo("4100000001");
    }

    @Test
    @DisplayName("네이버로 처음 로그인하면 이메일 동의 여부에 맞춰 회원이 생긴다")
    void firstNaverLoginCreatesMember() {
        OAuth2User withEmail = login("naver", naverProfile("naver-sprout", "sprout@naver.com"));
        OAuth2User withoutEmail = login("naver", naverProfile("naver-quiet", null));

        Member first = members.findById(memberIdOf(withEmail)).orElseThrow();
        Member second = members.findById(memberIdOf(withoutEmail)).orElseThrow();
        assertThat(first.getSocialProvider()).isEqualTo(SocialProvider.NAVER);
        assertThat(first.getProviderId()).isEqualTo("naver-sprout");
        assertThat(first.getEmail()).isEqualTo("sprout@naver.com");
        assertThat(second.getEmail()).isNull();
        assertThat(needsNickname(withEmail)).isTrue();
        assertThat(needsNickname(withoutEmail)).isTrue();
    }

    @Test
    @DisplayName("같은 소셜 계정으로 다시 로그인하면 같은 회원이고, 닉네임을 저장한 뒤에는 needsNickname=false 라 바로 홈으로 간다")
    void returningLoginFindsSameMember() throws Exception {
        Long memberId = memberIdOf(login("kakao", kakaoProfile(4100000002L, null)));

        OAuth2User beforeNickname = login("kakao", kakaoProfile(4100000002L, null));
        assertThat(memberIdOf(beforeNickname)).isEqualTo(memberId);
        assertThat(needsNickname(beforeNickname)).isTrue();

        Member member = members.findById(memberId).orElseThrow();
        mvc.perform(patch("/members/me/nickname").with(as(member)).with(json(Map.of("nickname", "sprout"))))
                .andExpect(okEnvelope());

        OAuth2User afterNickname = login("kakao", kakaoProfile(4100000002L, null));
        assertThat(memberIdOf(afterNickname)).isEqualTo(memberId);
        assertThat(needsNickname(afterNickname)).isFalse();
        assertThat(members.findBySocialProviderAndProviderId(SocialProvider.KAKAO, "4100000002"))
                .get().extracting(Member::getId).isEqualTo(memberId);
    }

    @Test
    @DisplayName("카카오와 네이버에서 같은 id 값이 와도 프로바이더가 다르면 다른 회원이다")
    void sameIdOnDifferentProvidersAreDifferentMembers() {
        Long kakao = memberIdOf(login("kakao", kakaoProfile(4100000003L, null)));
        Long naver = memberIdOf(login("naver", naverProfile("4100000003", null)));

        assertThat(kakao).isNotEqualTo(naver);
    }

    @Test
    @DisplayName("소셜 로그인 콜백이 실패하면(저장된 인가 요청 없음) FE 로그인 페이지(?error=oauth)로 302")
    void failedCallbackRedirectsToLogin() throws Exception {
        mvc.perform(get("/login/oauth2/code/kakao").param("code", "x").param("state", "y"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(FAILURE_REDIRECT));
        mvc.perform(get("/login/oauth2/code/naver").param("code", "x").param("state", "y"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(FAILURE_REDIRECT));
    }

    @Test
    @DisplayName("소셜 로그인 진입(/oauth2/authorization/kakao)은 카카오 인가 페이지로 302")
    void authorizationEntryRedirectsToProvider() throws Exception {
        String location = mvc.perform(get("/oauth2/authorization/kakao"))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();

        assertThat(location).startsWith("https://example.com/kakao/authorize");
        assertThat(location).contains("client_id=test-kakao-client-id", "response_type=code", "state=");
    }
}
