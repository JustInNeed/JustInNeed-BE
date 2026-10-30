package com.justinneed.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.service.LoginCodeService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("소셜 로그인 중 DB 문제")
class OAuthFailurePathTest {

    private static final String SUCCESS = "http://localhost:3000/oauth/callback";
    private static final String FAILURE = "http://localhost:3000/login?error=oauth";
    private static final Map<String, Object> PROFILE = Map.of("id", 91L);

    @Mock
    private MemberRepository members;

    @Mock
    private LoginCodeService loginCodes;

    @Test
    @DisplayName("회원 조회 중 DB 오류는 인증 실패로 바뀌어 실패 핸들러가 로그인 화면으로 보낸다")
    void databaseErrorBecomesAuthenticationFailure() {
        when(members.findBySocialProviderAndProviderId(any(), any())).thenThrow(new QueryTimeoutException("timeout"));
        CustomOAuth2UserService service = new CustomOAuth2UserService(members);

        assertThatThrownBy(() -> service.resolve("kakao", PROFILE))
                .isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    @DisplayName("같은 계정의 첫 로그인이 동시에 들어와 유니크에 걸리면 먼저 만들어진 회원으로 로그인한다")
    void concurrentFirstLoginUsesExistingMember() {
        Member winner = new Member(SocialProvider.KAKAO, "91", null, null);
        ReflectionTestUtils.setField(winner, "id", 7L);
        when(members.findBySocialProviderAndProviderId(SocialProvider.KAKAO, "91"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(members.save(any(Member.class))).thenThrow(new DataIntegrityViolationException("uk_member_provider"));
        CustomOAuth2UserService service = new CustomOAuth2UserService(members);

        var principal = service.resolve("kakao", PROFILE);

        assertThat(principal.getAttributes().get(CustomOAuth2UserService.ATTR_MEMBER_ID)).isEqualTo(7L);
    }

    @Test
    @DisplayName("로그인 코드 저장이 실패하면 500 이 아니라 로그인 화면(?error=oauth)으로 돌아간다")
    void codeIssueFailureRedirectsToLogin() throws Exception {
        when(loginCodes.issue(anyLong(), anyBoolean())).thenThrow(new QueryTimeoutException("timeout"));
        OAuth2SuccessHandler handler = new OAuth2SuccessHandler(loginCodes, SUCCESS, FAILURE);
        var principal = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of(CustomOAuth2UserService.ATTR_MEMBER_ID, 7L,
                        CustomOAuth2UserService.ATTR_NEEDS_NICKNAME, false,
                        CustomOAuth2UserService.ATTR_PROVIDER_ID, "91"),
                CustomOAuth2UserService.ATTR_PROVIDER_ID);
        var response = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(new MockHttpServletRequest(), response,
                new TestingAuthenticationToken(principal, null));

        assertThat(response.getRedirectedUrl()).isEqualTo(FAILURE);
    }
}
