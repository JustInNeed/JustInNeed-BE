package com.justinneed.session.management.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.dto.SessionUpdateRequest;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
class SessionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    private BrowsingSession session;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                1L, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        sessionRepository.deleteAll();

        session = new BrowsingSession(1L, "Spring 조사", LocalDateTime.of(2026, 6, 1, 10, 0));
        session.complete(LocalDateTime.of(2026, 6, 1, 10, 30), 3);
        session.update(null, null, false, true, List.of("Spring", "JPA"), null);
        session.replaceSources(List.of(new Source("Spring Docs", "https://spring.io", "Spring reference")));
        new Summary(session, "Spring 요약", "## Spring\n본문");
        sessionRepository.save(session);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void getSessionsReturnsList() throws Exception {
        mockMvc.perform(get("/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].title").value("Spring 조사"));
    }

    @Test
    void getSessionReturnsSummaryAndSources() throws Exception {
        mockMvc.perform(get("/sessions/{id}", session.getId()))
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
                List.of("Java"),
                null
        );

        mockMvc.perform(patch("/sessions/{id}", session.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("수정된 제목"))
                .andExpect(jsonPath("$.data.isPublic").value(true))
                .andExpect(jsonPath("$.data.isFavorite").value(false))
                .andExpect(jsonPath("$.data.summary.markdown").value("수정된 본문"));
    }

    @Test
    void deleteSessionSoftDeletes() throws Exception {
        mockMvc.perform(delete("/sessions/{id}", session.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/sessions/{id}", session.getId()))
                .andExpect(status().isNotFound());
    }
}
