package com.justinneed.session.management.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.dto.SessionUpdateRequest;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionControllerTest {

    private static final Long OTHER_USER_ID = Long.MAX_VALUE;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private MemberRepository memberRepository;

    private Long userId;
    private BrowsingSession session;
    private String bearer;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        // 공개 여부를 바꾸면 회원 행을 잠그므로 실제 회원이 있어야 한다
        userId = memberRepository.save(new Member(SocialProvider.KAKAO, "session-" + System.nanoTime(), null, null)).getId();
        bearer = "Bearer " + jwtTokenProvider.createAccessToken(userId);

        session = new BrowsingSession(userId, "Spring 조사", LocalDateTime.of(2026, 6, 1, 10, 0));
        session.complete(LocalDateTime.of(2026, 6, 1, 10, 30), 3);
        session.update(null, null, false, true, List.of("Spring", "JPA"));
        session.replaceSources(List.of(new Source("Spring Docs", "https://spring.io", "Spring reference")));
        new Summary(session, "## Spring\n본문");
        sessionRepository.save(session);
    }


    @Test
    void getSessionsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/sessions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSessionsReturnsList() throws Exception {
        mockMvc.perform(get("/sessions").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions", hasSize(1)))
                .andExpect(jsonPath("$.data.sessions[0].title").value("Spring 조사"))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.totalCount").value(1))
                .andExpect(jsonPath("$.data.favoriteCount").value(1));
    }

    @Test
    void getSessionsOnlyReturnsOwnSessions() throws Exception {
        String otherBearer = "Bearer " + jwtTokenProvider.createAccessToken(OTHER_USER_ID);

        mockMvc.perform(get("/sessions").header(HttpHeaders.AUTHORIZATION, otherBearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions", hasSize(0)))
                .andExpect(jsonPath("$.data.totalCount").value(0));
    }

    @Test
    @DisplayName("진행 중 세션은 맨 위, 종료 시각 없는 끝난 세션은 맨 뒤 — 이 DB(H2 기본 NULL 정렬)에서도 운영 Postgres 와 같은 순서")
    void getSessionsOrdersInProgressFirstAndMissingEndedAtLast() throws Exception {
        BrowsingSession analyzing = new BrowsingSession(userId, "분석 중", LocalDateTime.of(2026, 5, 1, 9, 0));
        ReflectionTestUtils.setField(analyzing, "status", SessionStatus.ANALYZING);
        sessionRepository.save(analyzing);
        BrowsingSession failedWithoutEnd = new BrowsingSession(userId, "실패", LocalDateTime.of(2026, 6, 2, 9, 0));
        ReflectionTestUtils.setField(failedWithoutEnd, "status", SessionStatus.FAILED);
        sessionRepository.save(failedWithoutEnd);

        mockMvc.perform(get("/sessions").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessions[*].id").value(contains(
                        analyzing.getId().intValue(), session.getId().intValue(), failedWithoutEnd.getId().intValue())));
    }

    @Test
    @DisplayName("GET /sessions/hashtags 는 /sessions/{id} 가 아니라 해시태그 후보로 매칭되고, 대소문자 무시 중복 없이 목록 순서로 준다")
    void getHashtagsReturnsDistinctTagsInListOrder() throws Exception {
        BrowsingSession newer = new BrowsingSession(userId, "JPA 심화", LocalDateTime.of(2026, 6, 2, 10, 0));
        newer.complete(LocalDateTime.of(2026, 6, 2, 11, 0), 2);
        newer.update(null, null, false, false, List.of("jpa", "Hibernate"));
        sessionRepository.save(newer);

        mockMvc.perform(get("/sessions/hashtags").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(contains("jpa", "Hibernate", "Spring")));
    }

    @Test
    void getSessionReturnsSummaryAndSources() throws Exception {
        mockMvc.perform(get("/sessions/{id}", session.getId()).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.markdown").value("## Spring\n본문"))
                .andExpect(jsonPath("$.data.sources[0].url").value("https://spring.io"));
    }

    @Test
    void updateSessionUpdatesEditableFields() throws Exception {
        SessionUpdateRequest request = new SessionUpdateRequest(
                "수정된 제목",
                "수정된 본문",
                true,
                false,
                List.of("Java")
        );

        mockMvc.perform(patch("/sessions/{id}", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("수정된 제목"))
                .andExpect(jsonPath("$.data.isPublic").value(true))
                .andExpect(jsonPath("$.data.isFavorite").value(false))
                .andExpect(jsonPath("$.data.summary.markdown").value("수정된 본문"))
                .andExpect(jsonPath("$.data.summary.insights").doesNotExist());
    }

    @Test
    void updateSessionRejectsBlankTitle() throws Exception {
        SessionUpdateRequest request = new SessionUpdateRequest("   ", null, null, null, null);

        mockMvc.perform(patch("/sessions/{id}", session.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void deleteSessionSoftDeletes() throws Exception {
        mockMvc.perform(delete("/sessions/{id}", session.getId()).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        mockMvc.perform(get("/sessions/{id}", session.getId()).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isNotFound());
    }
}
