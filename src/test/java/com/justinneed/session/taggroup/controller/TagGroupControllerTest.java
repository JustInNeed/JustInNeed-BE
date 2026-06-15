package com.justinneed.session.taggroup.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class TagGroupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private TagGroupRepository tagGroupRepository;

    private TagGroup springGroup;
    private TagGroup javaGroup;

    @BeforeEach
    void setUp() {
        tagGroupRepository.deleteAll();
        sessionRepository.deleteAll();

        BrowsingSession session = new BrowsingSession(1L, "JPA 기록", LocalDateTime.of(2026, 6, 1, 9, 0));
        session.complete(LocalDateTime.of(2026, 6, 1, 9, 30), 2);
        session.update(null, null, false, false, List.of("Spring", "JPA"));
        sessionRepository.save(session);

        springGroup = tagGroupRepository.save(new TagGroup(1L, "Spring", List.of("Spring"), 0));
        javaGroup = tagGroupRepository.save(new TagGroup(1L, "Java", List.of("Java"), 1));
    }

    @Test
    void getTagGroupsReturnsMatchingSessions() throws Exception {
        mockMvc.perform(get("/tag-groups"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].sessions", hasSize(1)))
                .andExpect(jsonPath("$.data[1].sessions", hasSize(0)));
    }

    @Test
    void createTagGroup() throws Exception {
        TagGroupCreateRequest request = new TagGroupCreateRequest("Backend", List.of("Backend"), null);

        mockMvc.perform(post("/tag-groups")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Backend"))
                .andExpect(jsonPath("$.data.position").value(2));
    }

    @Test
    void updateOrder() throws Exception {
        TagGroupOrderRequest request = new TagGroupOrderRequest(List.of(javaGroup.getId(), springGroup.getId()));

        mockMvc.perform(patch("/tag-groups/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(javaGroup.getId()));
    }
}
