package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.TokenHashes;
import com.justinneed.auth.oauth.CustomOAuth2UserService;
import com.justinneed.auth.oauth.OAuth2SuccessHandler;
import com.justinneed.auth.repository.LoginCodeRepository;
import com.justinneed.auth.service.LegacyWithdrawnMemberCleanup;
import com.justinneed.auth.service.LoginCodeCleanupScheduler;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 (/login → 카카오 · 네이버 → /oauth/callback?code= → POST /auth/token).
 * 프로바이더 호출(토큰 · 프로필 조회)은 건너뛰고, 프로필 응답 JSON 으로 회원 조회 · 생성 단계부터 확인한다.
 * FE: src/app/oauth/callback (code 를 받아 POST /auth/token 으로 토큰 교환).
 */
@DisplayName("소셜 로그인")
class LoginScenarioTest extends ScenarioTestSupport {

    private static final String SUCCESS_REDIRECT = "http://localhost:3000/oauth/callback";
    private static final String FAILURE_REDIRECT = "http://localhost:3000/login?error=oauth";
    private static final String INVALID_LOGIN_CODE_MESSAGE = "로그인 정보가 만료되었어요. 다시 로그인해 주세요.";

    @Autowired
    private CustomOAuth2UserService oAuth2UserService;

    @Autowired
    private OAuth2SuccessHandler successHandler;

    @Autowired
    private LoginCodeRepository loginCodes;

    @Autowired
    private LoginCodeCleanupScheduler loginCodeCleanupScheduler;

    @Autowired
    private LegacyWithdrawnMemberCleanup legacyWithdrawnMemberCleanup;

    @PersistenceContext
    private EntityManager entityManager;

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

    /** 로그인 성공 후 SuccessHandler 가 보내는 FE 콜백 리다이렉트 URL. */
    private String successRedirect(OAuth2User principal) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/kakao");
        MockHttpServletResponse response = new MockHttpServletResponse();
        successHandler.onAuthenticationSuccess(request, response,
                new OAuth2AuthenticationToken(principal, principal.getAuthorities(), "kakao"));
        return response.getRedirectedUrl();
    }

    /** 로그인 → 리다이렉트 URL 의 일회용 코드. */
    private String loginCode(OAuth2User principal) throws Exception {
        return UriComponentsBuilder.fromUriString(successRedirect(principal)).build().getQueryParams().getFirst("code");
    }

    private ResultActions exchange(String code) throws Exception {
        return mvc.perform(post("/auth/token").with(json(code == null ? "{}" : Map.of("code", code))));
    }

    private void withdraw(Member member) throws Exception {
        mvc.perform(delete("/settings/account").with(as(member)).with(json(Map.of("confirmText", "WITHDRAW"))))
                .andExpect(okEnvelope());
    }

    /** 익명화 도입 전 방식으로 탈퇴한 행: withdrawn_at 만 채워지고 소셜 식별자 · 닉네임 · 이메일은 그대로 남아 있다. */
    private void withdrawLikeBefore(Member member) {
        entityManager.flush();
        entityManager.createQuery(
                        "update Member m set m.withdrawnAt = :now, m.withdrawalReason = :reason where m.id = :id")
                .setParameter("now", LocalDateTime.now())
                .setParameter("reason", "예전 탈퇴")
                .setParameter("id", member.getId())
                .executeUpdate();
        entityManager.clear();
    }

    private boolean nicknameAvailable(Member viewer, String nickname) throws Exception {
        return read(mvc.perform(get("/members/nickname/available").param("nickname", nickname).with(as(viewer)))
                .andExpect(okEnvelope()), "$.data.available");
    }

    /** 저장된 코드 행의 만료 시각을 지난 시각으로 돌린다 (60초 기다리는 대신). */
    private void expireLoginCodes() {
        entityManager.flush();
        entityManager.createQuery("update LoginCode c set c.expiresAt = :past")
                .setParameter("past", LocalDateTime.now().minusSeconds(1))
                .executeUpdate();
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

    @Test
    @DisplayName("로그인에 성공하면 FE 콜백으로 일회용 코드만 담아 302 — 토큰 · needsNickname 은 URL 에 없다")
    void successRedirectCarriesOnlyCode() throws Exception {
        String redirect = successRedirect(login("kakao", kakaoProfile(4100000010L, null)));

        assertThat(redirect).startsWith(SUCCESS_REDIRECT + "?code=");
        MultiValueMap<String, String> query = UriComponentsBuilder.fromUriString(redirect).build().getQueryParams();
        assertThat(query.keySet()).containsExactly("code");
        assertThat(redirect).doesNotContain("accessToken", "refreshToken", "needsNickname");
        // 32바이트 난수 → base64url(패딩 없음) 43자
        String code = query.getFirst("code");
        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        // DB 에는 원문이 아니라 SHA-256 해시만 남는다
        assertThat(loginCodes.existsById(TokenHashes.sha256(code))).isTrue();
        assertThat(loginCodes.existsById(code)).isFalse();
    }

    @Test
    @DisplayName("처음 가입한 회원: 코드를 POST /auth/token 으로 바꾸면 토큰과 needsNickname=true 가 오고, 그 토큰으로 내 정보를 연다")
    void exchangeCodeForNewMember() throws Exception {
        OAuth2User principal = login("kakao", kakaoProfile(4100000011L, null));
        String code = loginCode(principal);

        var exchanged = exchange(code)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").isString())
                .andExpect(jsonPath("$.data.needsNickname").value(true));
        String accessToken = read(exchanged, "$.data.accessToken");
        String refreshToken = read(exchanged, "$.data.refreshToken");

        mvc.perform(get("/members/me").with(bearerToken(accessToken)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.id").value(memberIdOf(principal)))
                .andExpect(jsonPath("$.data.nickname").value(nullValue()));
        assertThat(jwt.isRefreshToken(refreshToken)).isTrue();
        assertThat(jwt.getMemberId(refreshToken)).isEqualTo(memberIdOf(principal));
        assertThat(loginCodes.existsById(TokenHashes.sha256(code))).isFalse();
    }

    @Test
    @DisplayName("닉네임을 정한 회원: 다시 로그인해 코드를 바꾸면 needsNickname=false 라 바로 홈으로 간다")
    void exchangeCodeForReturningMember() throws Exception {
        Member member = members.findById(memberIdOf(login("naver", naverProfile("naver-back", null)))).orElseThrow();
        mvc.perform(patch("/members/me/nickname").with(as(member)).with(json(Map.of("nickname", "sprout"))))
                .andExpect(okEnvelope());

        String code = loginCode(login("naver", naverProfile("naver-back", null)));

        var exchanged = exchange(code)
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.needsNickname").value(false));
        mvc.perform(get("/members/me").with(bearerToken(read(exchanged, "$.data.accessToken"))))
                .andExpect(jsonPath("$.data.nickname").value("sprout"));
    }

    @Test
    @DisplayName("한 번 바꾼 코드로 다시 요청하면(새로고침 · 뒤로 가기) 401 과 '로그인 정보가 만료되었어요' 문구")
    void codeIsSingleUse() throws Exception {
        String code = loginCode(login("kakao", kakaoProfile(4100000012L, null)));

        exchange(code).andExpect(okEnvelope());
        exchange(code).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
    }

    @Test
    @DisplayName("60초가 지난 코드는 401")
    void expiredCodeIsRejected() throws Exception {
        String code = loginCode(login("kakao", kakaoProfile(4100000013L, null)));
        expireLoginCodes();

        exchange(code).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
    }

    @Test
    @DisplayName("코드를 받은 뒤 60초 안에 탈퇴하면 코드를 토큰으로 바꿀 수 없다")
    void withdrawnMemberCannotExchangeCode() throws Exception {
        OAuth2User principal = login("kakao", kakaoProfile(7101L, null));
        Member member = members.findById(memberIdOf(principal)).orElseThrow();
        String code = loginCode(principal);

        withdraw(member);

        exchange(code).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
    }

    @Test
    @DisplayName("코드가 없거나 · 비었거나 · 발급한 적 없는 값이면 401")
    void missingOrUnknownCodeIsRejected() throws Exception {
        mvc.perform(post("/auth/token"))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
        exchange(null).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
        exchange("").andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
        exchange("   ").andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
        exchange("not-issued-code").andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
    }

    @Test
    @DisplayName("교환하지 않고 만료된 코드 행은 정리 스케줄러가 지우고, 아직 유효한 코드는 남긴다")
    void cleanupRemovesExpiredCodes() throws Exception {
        String expired = loginCode(login("kakao", kakaoProfile(4100000014L, null)));
        expireLoginCodes();
        String valid = loginCode(login("kakao", kakaoProfile(4100000015L, null)));

        loginCodeCleanupScheduler.purgeExpiredCodes();

        assertThat(loginCodes.existsById(TokenHashes.sha256(expired))).isFalse();
        assertThat(loginCodes.existsById(TokenHashes.sha256(valid))).isTrue();
        exchange(valid).andExpect(okEnvelope());
    }

    @Test
    @DisplayName("탈퇴한 소셜 계정으로 다시 로그인하면 새 회원(닉네임 없음 · needsNickname=true)으로 가입되고, 예전 닉네임도 다시 쓸 수 있다")
    void withdrawnAccountSignsUpAgain() throws Exception {
        Long oldId = memberIdOf(login("kakao", kakaoProfile(4100000020L, "sprout@kakao.com")));
        Member old = members.findById(oldId).orElseThrow();
        mvc.perform(patch("/members/me/nickname").with(as(old)).with(json(Map.of("nickname", "sprout"))))
                .andExpect(okEnvelope());
        withdraw(old);

        Member anonymized = members.findById(oldId).orElseThrow();
        assertThat(anonymized.isWithdrawn()).isTrue();
        assertThat(anonymized.getProviderId()).isEqualTo("withdrawn:" + oldId);
        assertThat(anonymized.getNickname()).isNull();
        assertThat(anonymized.getEmail()).isNull();

        OAuth2User again = login("kakao", kakaoProfile(4100000020L, "sprout@kakao.com"));
        Long newId = memberIdOf(again);
        assertThat(newId).isNotEqualTo(oldId);
        assertThat(needsNickname(again)).isTrue();
        Member rejoined = members.findById(newId).orElseThrow();
        assertThat(rejoined.getProviderId()).isEqualTo("4100000020");
        assertThat(rejoined.getNickname()).isNull();
        assertThat(rejoined.getEmail()).isEqualTo("sprout@kakao.com");

        var exchanged = exchange(loginCode(again))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.needsNickname").value(true));
        String accessToken = read(exchanged, "$.data.accessToken");
        mvc.perform(get("/members/me").with(bearerToken(accessToken)))
                .andExpect(jsonPath("$.data.id").value(newId))
                .andExpect(jsonPath("$.data.nickname").value(nullValue()));
        mvc.perform(patch("/members/me/nickname").with(bearerToken(accessToken)).with(json(Map.of("nickname", "sprout"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nickname").value("sprout"));
    }

    @Test
    @DisplayName("익명화 전에 탈퇴해 소셜 id · 닉네임이 남은 행도, 다시 로그인하면 그 행을 비우고 새 회원으로 가입시켜 예전 닉네임이 풀린다")
    void legacyWithdrawnAccountSignsUpAgain() throws Exception {
        Member legacy = members.saveAndFlush(new Member(SocialProvider.NAVER, "naver-legacy", "oldname", "old@naver.com"));
        Member someone = member(null);
        withdrawLikeBefore(legacy);
        assertThat(nicknameAvailable(someone, "oldname")).isFalse();

        OAuth2User again = login("naver", naverProfile("naver-legacy", "old@naver.com"));

        assertThat(memberIdOf(again)).isNotEqualTo(legacy.getId());
        assertThat(needsNickname(again)).isTrue();
        Member old = members.findById(legacy.getId()).orElseThrow();
        assertThat(old.getProviderId()).isEqualTo("withdrawn:" + legacy.getId());
        assertThat(old.getNickname()).isNull();
        assertThat(old.getEmail()).isNull();
        assertThat(old.isWithdrawn()).isTrue();
        assertThat(nicknameAvailable(someone, "oldname")).isTrue();
        exchange(loginCode(again)).andExpect(jsonPath("$.data.needsNickname").value(true));
    }

    @Test
    @DisplayName("앱 시작 시 익명화 전에 탈퇴한 행을 한 번에 익명화한다 (활동 중 · 이미 익명화된 회원은 그대로, 여러 번 돌려도 같음)")
    void startupCleanupAnonymizesLegacyWithdrawnMembers() throws Exception {
        Member legacy = members.saveAndFlush(new Member(SocialProvider.KAKAO, "4100000030", "legacy1", "l@kakao.com"));
        Member active = members.saveAndFlush(new Member(SocialProvider.KAKAO, "4100000031", "active1", "a@kakao.com"));
        Member recent = members.saveAndFlush(new Member(SocialProvider.KAKAO, "4100000032", "recent1", null));
        withdraw(recent);
        withdrawLikeBefore(legacy);

        legacyWithdrawnMemberCleanup.run(null);
        legacyWithdrawnMemberCleanup.run(null);
        entityManager.flush();
        entityManager.clear();

        Member cleaned = members.findById(legacy.getId()).orElseThrow();
        assertThat(cleaned.getProviderId()).isEqualTo("withdrawn:" + legacy.getId());
        assertThat(cleaned.getNickname()).isNull();
        assertThat(cleaned.getEmail()).isNull();
        assertThat(cleaned.isWithdrawn()).isTrue();
        Member untouched = members.findById(active.getId()).orElseThrow();
        assertThat(untouched.getProviderId()).isEqualTo("4100000031");
        assertThat(untouched.getNickname()).isEqualTo("active1");
        assertThat(members.findById(recent.getId()).orElseThrow().getProviderId())
                .isEqualTo("withdrawn:" + recent.getId());
    }
}
