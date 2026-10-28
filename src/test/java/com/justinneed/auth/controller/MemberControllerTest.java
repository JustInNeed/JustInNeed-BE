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
}
