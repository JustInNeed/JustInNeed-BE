package com.justinneed.auth.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Member me;
    private String bearer;

    @BeforeEach
    void setUp() {
        memberRepository.deleteAll();
        me = memberRepository.save(new Member(SocialProvider.KAKAO, "kakao-1", null, null));
        memberRepository.save(new Member(SocialProvider.NAVER, "naver-1", "taken", null));
        bearer = "Bearer " + jwtTokenProvider.createAccessToken(me.getId());
    }

    @Test
    void getMeReturnsNullNicknameBeforeOnboarding() throws Exception {
        mockMvc.perform(get("/members/me").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(me.getId()))
                .andExpect(jsonPath("$.data.nickname").isEmpty())
                .andExpect(jsonPath("$.data.socialProvider").value("KAKAO"));
    }

    @Test
    void updateNicknameSucceeds() throws Exception {
        mockMvc.perform(patch("/members/me/nickname")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"yesong1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("yesong1"));
    }

    @Test
    void updateNicknameRejectsInvalidFormat() throws Exception {
        mockMvc.perform(patch("/members/me/nickname")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"1abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void updateNicknameRejectsDuplicateIgnoringCase() throws Exception {
        mockMvc.perform(patch("/members/me/nickname")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"TAKEN\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void nicknameAvailableWhenUnused() throws Exception {
        mockMvc.perform(get("/members/nickname/available")
                        .param("nickname", "yesong1")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nickname").value("yesong1"))
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    void nicknameUnavailableWhenTakenIgnoringCase() throws Exception {
        mockMvc.perform(get("/members/nickname/available")
                        .param("nickname", "TAKEN")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.nickname").value("TAKEN"))
                .andExpect(jsonPath("$.data.available").value(false));
    }

    @Test
    void ownNicknameIsAvailable() throws Exception {
        String takenOwnerBearer = "Bearer " + jwtTokenProvider.createAccessToken(
                memberRepository.findBySocialProviderAndProviderId(SocialProvider.NAVER, "naver-1")
                        .orElseThrow().getId());

        mockMvc.perform(get("/members/nickname/available")
                        .param("nickname", "Taken")
                        .header(HttpHeaders.AUTHORIZATION, takenOwnerBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    void nicknameAvailabilityRejectsInvalidFormat() throws Exception {
        mockMvc.perform(get("/members/nickname/available")
                        .param("nickname", "1abc")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("닉네임은 영문으로 시작하는 3~8자(영문·숫자)여야 합니다."));
    }

    @Test
    void nicknameAvailabilityRejectsMissingParam() throws Exception {
        mockMvc.perform(get("/members/nickname/available")
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("닉네임을 입력해 주세요."));
    }

    @Test
    void nicknameAvailabilityRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/members/nickname/available").param("nickname", "yesong1"))
                .andExpect(status().isUnauthorized());
    }
}
