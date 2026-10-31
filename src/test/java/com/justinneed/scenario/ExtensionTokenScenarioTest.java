package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.RefreshToken;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.TokenHashes;
import com.justinneed.auth.repository.RefreshTokenRepository;
import com.justinneed.auth.service.AuthTokenService;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 확장 전용 토큰 (웹이 POST /auth/extension-code 로 받은 코드를 확장이 POST /auth/token 으로 교환).
 * 웹과 확장은 refresh 토큰이 따로라 재발급이 서로 영향을 주지 않고, 같은 묶음이라 로그아웃은 함께 된다.
 */
@DisplayName("확장 전용 토큰")
class ExtensionTokenScenarioTest extends ScenarioTestSupport {

    private static final String LOGIN_EXPIRED_MESSAGE = "로그인이 만료되었습니다. 다시 로그인해 주세요.";
    private static final String INVALID_REFRESH_MESSAGE = "유효하지 않은 리프레시 토큰입니다.";
    private static final String INVALID_LOGIN_CODE_MESSAGE = "로그인 정보가 만료되었어요. 다시 로그인해 주세요.";

    @Autowired
    private AuthTokenService authTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokens;

    private ResultActions requestCode(String accessToken) throws Exception {
        return mvc.perform(post("/auth/extension-code").with(bearerToken(accessToken)));
    }

    private String code(TokenResponse web) throws Exception {
        return read(requestCode(web.accessToken()).andExpect(okEnvelope()), "$.data.code");
    }

    private ResultActions exchange(String code) throws Exception {
        return mvc.perform(post("/auth/token").with(json(Map.of("code", code))));
    }

    private TokenResponse connect(TokenResponse web) throws Exception {
        ResultActions result = exchange(code(web)).andExpect(okEnvelope());
        return new TokenResponse(read(result, "$.data.accessToken"), read(result, "$.data.refreshToken"));
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/auth/refresh").with(json(Map.of("refreshToken", refreshToken))));
    }

    private TokenResponse refreshed(TokenResponse tokens) throws Exception {
        ResultActions result = refresh(tokens.refreshToken()).andExpect(okEnvelope());
        return new TokenResponse(read(result, "$.data.accessToken"), read(result, "$.data.refreshToken"));
    }

    private ResultActions logout(TokenResponse tokens) throws Exception {
        return mvc.perform(post("/auth/logout").with(bearerToken(tokens.accessToken()))
                .with(json(Map.of("refreshToken", tokens.refreshToken()))));
    }

    @Test
    @DisplayName("웹이 받은 코드를 확장이 교환하면 웹과 다른 토큰을 받고 같은 회원으로 API 를 쓴다")
    void connectExtension() throws Exception {
        Member me = member("sprout");
        TokenResponse web = authTokenService.issue(me.getId());

        requestCode(web.accessToken())
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.code").isString())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(60));
        TokenResponse extension = connect(web);

        assertThat(extension.refreshToken()).isNotEqualTo(web.refreshToken());
        mvc.perform(get("/members/me").with(bearerToken(extension.accessToken())))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.id").value(me.getId()));
        mvc.perform(get("/members/me").with(bearerToken(web.accessToken()))).andExpect(okEnvelope());
    }

    @Test
    @DisplayName("웹과 확장은 번갈아 재발급해도 서로 로그아웃시키지 않는다")
    void refreshIsIndependent() throws Exception {
        TokenResponse web = authTokenService.issue(member("sprout").getId());
        TokenResponse extension = connect(web);

        TokenResponse web2 = refreshed(web);
        TokenResponse extension2 = refreshed(extension);
        TokenResponse web3 = refreshed(web2);
        refreshed(extension2);

        refresh(web2.refreshToken()).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
        code(web3);
    }

    @Test
    @DisplayName("웹에서 로그아웃하면 재발급을 거친 확장 토큰도 재발급이 막힌다")
    void webLogoutEndsExtension() throws Exception {
        Member me = member("sprout");
        TokenResponse web = authTokenService.issue(me.getId());
        TokenResponse extension = refreshed(connect(web));

        logout(refreshed(web)).andExpect(okEnvelope());

        refresh(extension.refreshToken()).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
        assertThat(refreshTokens.findAll()).noneMatch(token -> token.getMemberId().equals(me.getId()));
    }

    @Test
    @DisplayName("확장에서 로그아웃하면 웹 토큰도 재발급이 막힌다")
    void extensionLogoutEndsWeb() throws Exception {
        TokenResponse web = authTokenService.issue(member("sprout").getId());
        TokenResponse extension = connect(web);

        logout(extension).andExpect(okEnvelope());

        refresh(web.refreshToken()).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_REFRESH_MESSAGE));
    }

    @Test
    @DisplayName("로그아웃은 묶음 하나만 끝낸다: 다른 기기 로그인과 그 확장은 유지된다")
    void logoutKeepsOtherGroups() throws Exception {
        Member me = member("sprout");
        TokenResponse laptop = authTokenService.issue(me.getId());
        TokenResponse desktop = authTokenService.issue(me.getId());
        TokenResponse desktopExtension = connect(desktop);

        logout(laptop).andExpect(okEnvelope());

        refreshed(desktop);
        refreshed(desktopExtension);
    }

    @Test
    @DisplayName("코드는 한 번만 쓸 수 있다")
    void codeIsSingleUse() throws Exception {
        String code = code(authTokenService.issue(member("sprout").getId()));

        exchange(code).andExpect(okEnvelope());
        exchange(code).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
    }

    @Test
    @DisplayName("토큰 없이 · refresh 토큰으로 · 로그아웃한 access 토큰으로는 코드를 받을 수 없다")
    void codeRequiresLogin() throws Exception {
        TokenResponse web = authTokenService.issue(member("sprout").getId());

        mvc.perform(post("/auth/extension-code")).andExpect(status().isUnauthorized());
        requestCode(web.refreshToken()).andExpect(status().isUnauthorized());
        logout(web).andExpect(okEnvelope());
        requestCode(web.accessToken()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("확장에서 로그아웃한 뒤 웹에 남은 access 토큰으로는 코드를 받을 수 없다")
    void codeRequiresActiveGroup() throws Exception {
        TokenResponse web = authTokenService.issue(member("sprout").getId());

        logout(connect(web)).andExpect(okEnvelope());

        requestCode(web.accessToken()).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, LOGIN_EXPIRED_MESSAGE));
    }

    @Test
    @DisplayName("코드를 받은 뒤 로그아웃하면 그 코드는 교환되지 않는다")
    void codeDiesWithLogout() throws Exception {
        TokenResponse web = authTokenService.issue(member("sprout").getId());
        String code = code(web);

        logout(web).andExpect(okEnvelope());

        exchange(code).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, INVALID_LOGIN_CODE_MESSAGE));
    }

    @Test
    @DisplayName("탈퇴하면 확장 토큰도 재발급 · 사용이 막힌다")
    void withdrawalEndsExtension() throws Exception {
        Member me = member("sprout");
        TokenResponse web = authTokenService.issue(me.getId());
        TokenResponse extension = connect(web);

        mvc.perform(delete("/settings/account").with(bearerToken(web.accessToken()))
                .with(json(Map.of("confirmText", "WITHDRAW")))).andExpect(okEnvelope());

        refresh(extension.refreshToken()).andExpect(status().isUnauthorized());
        mvc.perform(get("/members/me").with(bearerToken(extension.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("닉네임을 정하기 전이면 교환 응답의 needsNickname 이 true 다")
    void needsNickname() throws Exception {
        Member me = member(null, SocialProvider.KAKAO, null);
        TokenResponse web = authTokenService.issue(me.getId());

        exchange(code(web)).andExpect(okEnvelope()).andExpect(jsonPath("$.data.needsNickname").value(true));
    }

    @Test
    @DisplayName("묶음 도입 전 토큰: 코드는 401, 재발급하면 묶음이 생겨 연결되고 로그아웃도 함께 된다")
    void tokensWithoutGroup() throws Exception {
        Member me = member("sprout");
        String oldAccess = jwt.createAccessToken(me.getId());
        String oldRefresh = jwt.createRefreshToken(me.getId());
        refreshTokens.save(new RefreshToken(me.getId(), TokenHashes.sha256(oldRefresh),
                LocalDateTime.now().plusDays(1)));

        mvc.perform(get("/members/me").with(bearerToken(oldAccess))).andExpect(okEnvelope());
        requestCode(oldAccess).andExpect(errorEnvelope(HttpStatus.UNAUTHORIZED, LOGIN_EXPIRED_MESSAGE));

        TokenResponse web = refreshed(new TokenResponse(oldAccess, oldRefresh));
        TokenResponse extension = connect(web);
        logout(web).andExpect(okEnvelope());

        refresh(extension.refreshToken()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("묶음 도입 전 refresh 토큰으로 로그아웃해도 그 토큰만 지워진다")
    void logoutWithoutGroup() throws Exception {
        Member me = member("sprout");
        TokenResponse other = authTokenService.issue(me.getId());
        String oldRefresh = jwt.createRefreshToken(me.getId());
        refreshTokens.save(new RefreshToken(me.getId(), TokenHashes.sha256(oldRefresh),
                LocalDateTime.now().plusDays(1)));

        logout(new TokenResponse(jwt.createAccessToken(me.getId()), oldRefresh)).andExpect(okEnvelope());

        refresh(oldRefresh).andExpect(status().isUnauthorized());
        refreshed(other);
    }
}
