package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AiCallbackControllerTest {

    private static final String TOKEN = "test-ai-internal-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private SessionRawPayloadRepository rawRepository;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        BrowsingSession session = new BrowsingSession(1L, "분석 전 제목", LocalDateTime.of(2026, 10, 30, 9, 59));
        session.markReady();
        session.startAnalysis(LocalDateTime.of(2026, 10, 30, 10, 30));
        sessionId = sessionRepository.save(session).getId();
        Map<String, Object> raw = objectMapper.readValue(AnalysisResultServiceTest.RAW_JSON, new TypeReference<>() {
        });
        rawRepository.save(new SessionRawPayload(sessionId, 1L, raw, LocalDateTime.of(2026, 10, 30, 10, 30)));
    }

    @Test
    void 내부_토큰이_없거나_틀리면_401() throws Exception {
        mockMvc.perform(result(AnalysisResultServiceTest.RESULT_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mockMvc.perform(result(AnalysisResultServiceTest.RESULT_JSON).header(AiInternalToken.HEADER, "wrong"))
                .andExpect(status().isUnauthorized());

        assertThat(sessionStatus()).isEqualTo(SessionStatus.ANALYZING);
    }

    @Test
    void 결과를_받으면_저장하고_COMPLETED() throws Exception {
        mockMvc.perform(result(AnalysisResultServiceTest.RESULT_JSON).header(AiInternalToken.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(sessionStatus()).isEqualTo(SessionStatus.COMPLETED);
    }

    @Test
    void LLM_실패_결과면_422_로_답하고_FAILED() throws Exception {
        String fallback = AnalysisResultServiceTest.RESULT_JSON.replace("\"source\": \"llm\"", "\"source\": \"fallback\"");

        mockMvc.perform(result(fallback).header(AiInternalToken.HEADER, TOKEN))
                .andExpect(status().isUnprocessableContent());

        assertThat(sessionStatus()).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void 실패_콜백을_받으면_FAILED() throws Exception {
        mockMvc.perform(post("/internal/ai/sessions/{id}/failure", sessionId)
                        .header(AiInternalToken.HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"RuntimeError: boom\"}"))
                .andExpect(status().isOk());

        assertThat(sessionStatus()).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void 분석_중이_아니면_409() throws Exception {
        sessionRepository.findById(sessionId).orElseThrow().failAnalysis();

        mockMvc.perform(result(AnalysisResultServiceTest.RESULT_JSON).header(AiInternalToken.HEADER, TOKEN))
                .andExpect(status().isConflict());
    }

    private MockHttpServletRequestBuilder result(String json) {
        return post("/internal/ai/sessions/{id}/result", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
    }

    private SessionStatus sessionStatus() {
        return sessionRepository.findById(sessionId).orElseThrow().getStatus();
    }
}
