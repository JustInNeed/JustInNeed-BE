package com.justinneed.scenario;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;

/** AI 결과 콜백이 세션 상세에 남기는 값 (본문 · 출처 · 제목). 응답 모양은 pipeline/output.py 를 따른다. */
@DisplayName("AI 결과가 반영된 세션 상세")
class SessionResultScenarioTest extends ScenarioTestSupport {

    private static final String MAIN = "https://a.example.com/news";
    private static final String COPY = "https://b.example.com/copy";

    // pageId 가 url 과 다른 글도 있어 링크는 원본 meta 에서 찾아야 한다
    private static final String RAW = """
            {"session": {"endedAt": "2026-10-04T09:33:54Z"},
             "pages": [
               {"meta": {"pageId": "https://a.example.com/news", "url": "https://a.example.com/news", "title": "원문 기사",
                         "enteredAt": "2026-10-04T09:32:00Z"}},
               {"meta": {"pageId": "page-b", "url": "https://b.example.com/copy", "title": "옮겨 실은 기사",
                         "enteredAt": "2026-10-04T09:30:00Z"}}]}
            """;

    private static final String RESULT = """
            {"modelSource": "global", "scoreMethod": "C", "state": {"unitFeatures": []},
             "mindmap": {"structureSource": "llm",
                         "nodes": [{"id": "root:1", "type": "root", "keyword": "로마", "score": 1.0, "childCount": 0}],
                         "edges": []},
             "essay": {"source": "llm", "title": "%s",
                       "sections": [{"heading": "첫 소제목", "body": "첫 본문"}, {"heading": "둘째 소제목", "body": "둘째 본문"}]},
             "selected": [
               {"unitKey": "https://a.example.com/news#p1", "pageId": "https://a.example.com/news", "pid": "p1",
                "pageTitle": "원문 기사", "pageUrl": "https://a.example.com/news", "text": "대표 문단", "score": 3.0,
                "firstSeenAt": "2026-10-04T09:32:10+00:00",
                "mergedFrom": ["page-b#p9", "https://a.example.com/news#p2"]}],
             "analysis": {"units": [], "summary": {}}, "pages": []}
            """;

    @Autowired
    private SessionRawPayloadRepository rawPayloads;

    @Autowired
    private AiInternalToken internalToken;

    private Member me;
    private BrowsingSession session;

    @BeforeEach
    void seed() {
        me = member("sprout");
        session = sessionWithStatus(me, "분석 중", "2026-10-04T18:00:00", "2026-10-04T19:00:00",
                SessionStatus.ANALYZING, List.of());
        rawPayloads.saveAndFlush(new SessionRawPayload(session.getId(), me.getId(),
                objectMapper.readValue(RAW, new TypeReference<Map<String, Object>>() {
                }), LocalDateTime.now()));
    }

    private void callback(String title) throws Exception {
        mvc.perform(post("/internal/ai/sessions/{id}/result", session.getId())
                        .header(AiInternalToken.HEADER, internalToken.value())
                        .with(json(RESULT.formatted(title))))
                .andExpect(okEnvelope());
    }

    @Test
    @DisplayName("본문은 # 제목 없이 첫 ## 소제목부터 시작하고, 제목은 세션 제목으로만 남는다")
    void markdownHasNoTitle() throws Exception {
        callback("AI 대제목");

        mvc.perform(get("/sessions/{id}", session.getId()).with(as(me)))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.title").value("AI 대제목"))
                .andExpect(jsonPath("$.data.summary.markdown").value(startsWith("## 첫 소제목")))
                .andExpect(jsonPath("$.data.summary.markdown").value("## 첫 소제목\n\n첫 본문\n\n## 둘째 소제목\n\n둘째 본문"));
    }

    @Test
    @DisplayName("제목을 고쳐도 본문에 옛 제목이 남지 않는다")
    void titleEditLeavesNoOldTitle() throws Exception {
        callback("AI 대제목");

        mvc.perform(patch("/sessions/{id}", session.getId()).with(as(me)).with(json(Map.of("title", "내가 고친 제목"))))
                .andExpect(okEnvelope())
                .andExpect(jsonPath("$.data.title").value("내가 고친 제목"))
                .andExpect(jsonPath("$.data.summary.markdown").value(startsWith("## 첫 소제목")));
    }
}
