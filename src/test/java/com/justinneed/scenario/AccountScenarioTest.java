package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.service.AuthTokenService;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 로그인 · 회원가입(닉네임 입력) · 사이드바 프로필 · 토큰 재발급/로그아웃 화면.
 * FE: src/app/onboarding/nickname, src/lib/nickname.ts, src/lib/api/client.ts(401 → refresh 1회 → 로그인 화면).
 */
@DisplayName("회원가입 · 로그인 · 계정")
class AccountScenarioTest extends ScenarioTestSupport {

    private static final String NICKNAME_FORMAT_MESSAGE = "닉네임은 영문으로 시작하는 3~8자(영문·숫자)여야 합니다.";
    // FE NICKNAME_TAKEN_MESSAGE 와 같은 문구
    private static final String NICKNAME_TAKEN_MESSAGE = "이미 사용 중인 닉네임입니다.";
    private static final String INVALID_REFRESH_MESSAGE = "유효하지 않은 리프레시 토큰입니다.";

    @Autowired
    private AuthTokenService authTokenService;

    @Test
    @DisplayName("가입 직후 내 정보를 열면 nickname 이 null 이라 FE 가 닉네임 입력 화면으로 보낸다")
    void newMemberHasNoNickname() throws Exception {
        Member signedUp = member(null, SocialProvider.NAVER, "sprout@example.com");

        mvc.perform(get("/members/me").with(as(signedUp)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.message").value(nullValue()))
                .andExpect(jsonPath("$.data.id").value(signedUp.getId()))
                .andExpect(jsonPath("$.data.nickname").value(nullValue()))
                .andExpect(jsonPath("$.data.socialProvider").value("NAVER"))
                .andExpect(jsonPath("$.data.email").value("sprout@example.com"))
                // FE MemberResponse.joinedAt: 타임존 없는 ISO datetime
                .andExpect(jsonPath("$.data.joinedAt").value(matchesPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*")));
    }

    @Test
    @DisplayName("닉네임을 저장하면 응답과 내 정보에 바로 반영돼 사이드바에 보인다")
    void savedNicknameShowsUpInMe() throws Exception {
        Member signedUp = member(null);

        mvc.perform(patch("/members/me/nickname").with(as(signedUp)).with(json(Map.of("nickname", "sprout1"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.id").value(signedUp.getId()))
                .andExpect(jsonPath("$.data.nickname").value("sprout1"));

        mvc.perform(get("/members/me").with(as(signedUp)))
                .andExpect(jsonPath("$.data.nickname").value("sprout1"));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"ab", "abcdefghi", "1abc", "ab_c", "ab c", "sprout.kr", "wanderlust", "새싹"})
    @DisplayName("닉네임 형식(영문 시작 3~8자, 영문·숫자)이 틀리면 400 과 안내 문구를 받고 닉네임은 그대로다")
    void invalidNicknameIsRejected(String nickname) throws Exception {
        Member signedUp = member(null);

        mvc.perform(patch("/members/me/nickname").with(as(signedUp)).with(json(Map.of("nickname", nickname))))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, NICKNAME_FORMAT_MESSAGE));

        mvc.perform(get("/members/me").with(as(signedUp)))
                .andExpect(jsonPath("$.data.nickname").value(nullValue()));
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"abc", "abcdefgh", "Sprout27"})
    @DisplayName("경계값(3자 · 8자)과 대문자 · 숫자가 섞인 닉네임은 저장된다")
    void boundaryNicknamesAreAccepted(String nickname) throws Exception {
        Member signedUp = member(null);

        mvc.perform(patch("/members/me/nickname").with(as(signedUp)).with(json(Map.of("nickname", nickname))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nickname").value(nickname));
    }

    @Test
    @DisplayName("닉네임 칸을 비우고 저장하면 400 과 '닉네임을 입력해 주세요.' 문구를 받는다")
    void blankNicknameIsRejected() throws Exception {
        Member signedUp = member(null);

        mvc.perform(patch("/members/me/nickname").with(as(signedUp)).with(json(Map.of("nickname", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("닉네임을 입력해 주세요.")));
    }

    @Test
    @DisplayName("다른 사람이 쓰는 닉네임(대소문자 무시)으로 저장하면 409 와 FE 와 같은 '이미 사용 중인 닉네임입니다.' 를 받는다")
    void takenNicknameIsConflict() throws Exception {
        member("wanderer");
        Member me = member(null);

        mvc.perform(patch("/members/me/nickname").with(as(me)).with(json(Map.of("nickname", "WANDERER"))))
                .andExpect(errorEnvelope(HttpStatus.CONFLICT, NICKNAME_TAKEN_MESSAGE));
    }

    @Test
    @DisplayName("지금 내 닉네임을 대소문자만 바꿔 다시 저장하면 중복으로 막히지 않는다")
    void ownNicknameCanBeResaved() throws Exception {
        Member me = member("sprout");

        mvc.perform(patch("/members/me/nickname").with(as(me)).with(json(Map.of("nickname", "Sprout"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nickname").value("Sprout"));
    }

    @Test
    @DisplayName("토큰 없이 · 잘못된 토큰으로 · refresh 토큰으로 내 정보를 열면 401 이라 FE 가 재발급을 시도한다")
    void protectedApiWithoutValidAccessTokenIs401() throws Exception {
        Member me = member("sprout");

        mvc.perform(get("/members/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/members/me").with(bearerToken("not-a-jwt"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/members/me").with(bearerToken(jwt.createRefreshToken(me.getId()))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("access 토큰이 만료되면 refresh 토큰으로 새 토큰 쌍을 받고, 한 번 쓴 refresh 토큰은 다시 못 쓴다")
    void refreshRotatesTokens() throws Exception {
        Member me = member("sprout");
        TokenResponse login = authTokenService.issue(me.getId()); // 소셜 로그인 성공 시 발급되는 토큰
        // JWT 발급 시각이 초 단위라 같은 초에 재발급하면 같은 토큰이 나온다 → 1초 넘겨서 재발급
        Thread.sleep(1100);

        var refreshed = mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", login.refreshToken()))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.accessToken").isString())
                .andExpect(jsonPath("$.data.refreshToken").value(not(login.refreshToken())));
        String newAccessToken = read(refreshed, "$.data.accessToken");

        mvc.perform(get("/members/me").with(bearerToken(newAccessToken)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nickname").value("sprout"));
        mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", login.refreshToken()))))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
    }

    @Test
    @DisplayName("refresh 토큰이 없거나 · 형식이 틀리거나 · access 토큰을 보내면 401 이라 FE 가 로그인 화면으로 보낸다")
    void invalidRefreshIs401() throws Exception {
        Member me = member("sprout");

        mvc.perform(post("/auth/refresh").with(json("{}")))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
        mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", "not-a-jwt"))))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
        mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", jwt.createAccessToken(me.getId())))))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
    }

    @Test
    @DisplayName("로그아웃하면 지금 access 토큰과 refresh 토큰이 바로 무효가 된다")
    void logoutRevokesBothTokens() throws Exception {
        Member me = member("sprout");
        TokenResponse login = authTokenService.issue(me.getId());

        mvc.perform(post("/auth/logout").with(bearerToken(login.accessToken()))
                        .with(json(Map.of("refreshToken", login.refreshToken()))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data").value(nullValue()));

        mvc.perform(get("/members/me").with(bearerToken(login.accessToken()))).andExpect(status().isUnauthorized());
        mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", login.refreshToken()))))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
    }

    @Test
    @DisplayName("토큰 없이 로그아웃해도 200 이라 FE 는 항상 저장된 토큰을 지우고 나갈 수 있다")
    void logoutIsIdempotent() throws Exception {
        mvc.perform(post("/auth/logout")).andExpect(okEnvelope());
        // FE logout(undefined) → body {} 로 나간다
        mvc.perform(post("/auth/logout").with(json("{}"))).andExpect(okEnvelope());
        mvc.perform(post("/auth/logout").with(bearerToken("expired-or-garbage")).with(json(Map.of("refreshToken", "x"))))
                .andExpect(okEnvelope());
    }

    @Test
    @DisplayName("닉네임 중복 확인 버튼: 이미 쓰는 닉네임(대소문자 무시)이면 available=false, 아니면 true")
    void nicknameAvailability() throws Exception {
        member("wanderer");
        Member me = member(null);

        mvc.perform(get("/members/nickname/available").param("nickname", "WANDERER").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.nickname").value("WANDERER"))
                .andExpect(jsonPath("$.data.available").value(false));
        mvc.perform(get("/members/nickname/available").param("nickname", "sprout").with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.available").value(true));
        mvc.perform(get("/members/nickname/available").param("nickname", "1abc").with(as(me)))
                .andExpect(errorEnvelope(HttpStatus.BAD_REQUEST, NICKNAME_FORMAT_MESSAGE));
        assertThat(members.findById(me.getId()).orElseThrow().getNickname()).isNull();
    }
}
