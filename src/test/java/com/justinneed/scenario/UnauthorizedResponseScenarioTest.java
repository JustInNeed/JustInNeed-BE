package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.service.AuthTokenService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 로그인이 풀린 상태로 보호 API 를 부를 때의 401 응답 (MEM-2-2).
 * FE(src/lib/api/client.ts)는 401 이면 재발급을 시도하고, 실패하면 로그인 화면으로 보낸다.
 */
@DisplayName("로그인 만료 401 응답")
class UnauthorizedResponseScenarioTest extends ScenarioTestSupport {

    private static final String LOGIN_EXPIRED = "로그인이 만료되었습니다. 다시 로그인해 주세요.";
    private static final String BODY =
            "{\"success\":false,\"data\":null,\"message\":\"" + LOGIN_EXPIRED + "\"}";
    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    @Autowired
    private AuthTokenService authTokenService;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    /** 401 + 공통 에러 형식(본문 그대로) + UTF-8 JSON. 바이트를 UTF-8 로 풀어 한글이 그대로인지도 본다. */
    private void expectLoginExpired(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8"))
                .andExpect(content().json(BODY, JsonCompareMode.STRICT));
        byte[] raw = result.andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(raw, StandardCharsets.UTF_8)).contains(LOGIN_EXPIRED);
    }

    private ResultActions getMe(RequestPostProcessor token) throws Exception {
        return mvc.perform(get("/members/me").with(token));
    }

    @Test
    @DisplayName("토큰이 없으면 401 공통 에러 응답")
    void noToken() throws Exception {
        expectLoginExpired(mvc.perform(get("/members/me")));
    }

    @Test
    @DisplayName("JWT 가 아닌 값이면 401 공통 에러 응답")
    void malformedToken() throws Exception {
        expectLoginExpired(getMe(bearerToken("not-a-jwt")));
    }

    @Test
    @DisplayName("다른 키로 서명한 토큰이면 401 공통 에러 응답")
    void forgedToken() throws Exception {
        Member me = member("sprout");
        String otherKey = "another-secret-another-secret-another-secret-another-secret";
        JwtTokenProvider forger = new JwtTokenProvider(otherKey, 3600, 3600);

        expectLoginExpired(getMe(bearerToken(forger.createAccessToken(me.getId()))));
    }

    @Test
    @DisplayName("만료된 access 토큰이면 401 공통 에러 응답")
    void expiredToken() throws Exception {
        Member me = member("sprout");
        JwtTokenProvider expired = new JwtTokenProvider(jwtSecret, -60, -60);

        expectLoginExpired(getMe(bearerToken(expired.createAccessToken(me.getId()))));
    }

    @Test
    @DisplayName("로그아웃으로 무효화한 access 토큰이면 401 공통 에러 응답")
    void deniedToken() throws Exception {
        Member me = member("sprout");
        TokenResponse login = authTokenService.issue(me.getId());
        mvc.perform(post("/auth/logout").with(bearerToken(login.accessToken()))).andExpect(okEnvelope());

        expectLoginExpired(getMe(bearerToken(login.accessToken())));
    }

    @Test
    @DisplayName("refresh 토큰을 access 토큰처럼 보내면 401 공통 에러 응답")
    void refreshTokenUsedAsAccessToken() throws Exception {
        Member me = member("sprout");

        expectLoginExpired(getMe(bearerToken(jwt.createRefreshToken(me.getId()))));
    }

    @Test
    @DisplayName("탈퇴한 회원의 access 토큰이면 어떤 보호 API 든 401 공통 에러 응답")
    void withdrawnMemberToken() throws Exception {
        Member me = member("sprout");
        String phone = jwt.createAccessToken(me.getId());
        mvc.perform(delete("/settings/account").with(as(me)).with(json(Map.of("confirmText", "WITHDRAW"))))
                .andExpect(okEnvelope());

        for (String path : List.of("/members/me", "/sessions", "/tag-groups", "/settings/integrations")) {
            expectLoginExpired(mvc.perform(get(path).with(bearerToken(phone))));
        }
    }

    @Test
    @DisplayName("없는 경로 · actuator 처럼 컨트롤러가 없어도 인증이 먼저라 같은 401 공통 에러 응답")
    void otherProtectedPaths() throws Exception {
        expectLoginExpired(mvc.perform(get("/no-such-path")));
        expectLoginExpired(mvc.perform(get("/actuator/info")));
    }

    @Test
    @DisplayName("FE 출처(Origin)에서 온 요청의 401 에도 CORS 헤더가 붙어 FE 가 상태 코드를 읽을 수 있다")
    void corsHeadersOnUnauthorized() throws Exception {
        ResultActions result = mvc.perform(get("/members/me").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN));

        expectLoginExpired(result);
        result.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("재발급 API 는 공개 경로라 이 응답을 쓰지 않고 '유효하지 않은 리프레시 토큰입니다.' 를 그대로 준다")
    void refreshKeepsItsOwnMessage() throws Exception {
        mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", "not-a-jwt"))))
                .andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, "유효하지 않은 리프레시 토큰입니다."));
    }
}
