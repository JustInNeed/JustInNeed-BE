package com.justinneed.session.taggroup.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.taggroup.domain.TagGroup;
import com.justinneed.session.taggroup.dto.TagGroupCreateRequest;
import com.justinneed.session.taggroup.dto.TagGroupOrderRequest;
import com.justinneed.session.taggroup.repository.TagGroupRepository;
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
class TagGroupControllerTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private TagGroupRepository tagGroupRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private TagGroup springGroup;
    private TagGroup javaGroup;
    private String bearer;

    @BeforeEach
    void setUp() {
        tagGroupRepository.deleteAll();
        sessionRepository.deleteAll();
        bearer = "Bearer " + jwtTokenProvider.createAccessToken(USER_ID);

        BrowsingSession session = new BrowsingSession(USER_ID, "JPA 기록", LocalDateTime.of(2026, 6, 1, 9, 0));
        session.complete(LocalDateTime.of(2026, 6, 1, 9, 30), 2);
        session.update(null, null, false, false, List.of("Spring", "JPA"), null);
        sessionRepository.save(session);

        springGroup = tagGroupRepository.save(new TagGroup(USER_ID, "Spring", List.of("Spring"), 0));
        javaGroup = tagGroupRepository.save(new TagGroup(USER_ID, "Java", List.of("Java"), 1));
    }

    @Test
    void getTagGroupsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/tag-groups"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getTagGroupsReturnsMatchingSessions() throws Exception {
        mockMvc.perform(get("/tag-groups").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].sessions", hasSize(1)))
                .andExpect(jsonPath("$.data[1].sessions", hasSize(0)));
    }

    @Test
    void createTagGroupAppendsToEnd() throws Exception {
        TagGroupCreateRequest request = new TagGroupCreateRequest("Backend", List.of("Backend"), null);

        mockMvc.perform(post("/tag-groups")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Backend"))
                .andExpect(jsonPath("$.data.position").value(2));
    }

    @Test
    void createTagGroupAtPositionShiftsOthers() throws Exception {
        TagGroupCreateRequest request = new TagGroupCreateRequest("Backend", List.of("Backend"), 0);

        mockMvc.perform(post("/tag-groups")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(0));

        mockMvc.perform(get("/tag-groups").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].name").value("Backend"))
                .andExpect(jsonPath("$.data[1].id").value(springGroup.getId()))
                .andExpect(jsonPath("$.data[1].position").value(1))
                .andExpect(jsonPath("$.data[2].id").value(javaGroup.getId()))
                .andExpect(jsonPath("$.data[2].position").value(2));
    }

    @Test
    void createTagGroupRejectsOutOfRangePosition() throws Exception {
        TagGroupCreateRequest request = new TagGroupCreateRequest("Backend", List.of("Backend"), 5);

        mockMvc.perform(post("/tag-groups")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateOrder() throws Exception {
        TagGroupOrderRequest request = new TagGroupOrderRequest(List.of(javaGroup.getId(), springGroup.getId()));

        mockMvc.perform(patch("/tag-groups/order")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(javaGroup.getId()));
    }
}
