package com.justinneed.session.management.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.dto.SessionUpdateRequest;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private BrowsingSession session;
    private String bearer;

    @BeforeEach
    void setUp() {
        sessionRepository.deleteAll();
        bearer = "Bearer " + jwtTokenProvider.createAccessToken(USER_ID);

        session = new BrowsingSession(USER_ID, "Spring 조사", LocalDateTime.of(2026, 6, 1, 10, 0));
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
