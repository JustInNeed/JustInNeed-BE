package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * 수집 제외 URL 이 세션 원본 저장에 적용되는지 (X-SET-02). 확장 청크 2개(tricky.chunks.json, 페이지는 example.com/a 하나)를 보내
 * 합쳐진 원본에서 제외 URL 페이지가 빠지는지 본다. 사이트 주소만 바꿔 보낸다. 커밋 뒤 비동기 처리를 봐야 해서 SessionCollectionScenarioTest 처럼
 * 테스트 트랜잭션 없이 따로 DB 를 쓰고 매번 지운다.
 */
@TestPropertySource(
        properties =
                "spring.datasource.url=jdbc:h2:mem:excluded-url-collection;MODE=PostgreSQL;"
                        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("수집 제외 URL 적용 (청크 합치기 → 원본 저장)")
class ExcludedUrlCollectionScenarioTest extends ScenarioTestSupport {

    private static final String COLLECTOR = "00000000-tric-ky00-0000-000000000001";
    private static final String EXCLUDED_URLS = "/settings/collection/excluded-urls";
    // tricky 청크의 페이지 a 를 www.naver.com 으로 바꿨을 때의 pageId
    private static final String REMOVED_PAGE_ID = "https://www.naver.com|a";

    @MockitoBean private AiAnalysisClient aiClient;

    @Autowired private SessionRawPayloadRepository rawPayloads;

    @Autowired private JdbcTemplate jdbc;

    private Member me;
    private String chunksJson;

    @BeforeEach
    void seed() throws Exception {
        me = member("seeker");
        try (InputStream in =
                getClass().getResourceAsStream("/chunk-assembly/tricky.chunks.json")) {
            chunksJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void cleanUp() {
        for (String table :
                List.of(
                        "summaries",
                        "session_results",
                        "session_raw_payloads",
                        "session_raw_chunks",
                        "session_collections",
                        "community_cards",
                        "sessions",
                        "excluded_urls",
                        "members")) {
            jdbc.update("delete from " + table);
        }
    }

    // 청크 속 https://example.com 을 site 로 바꿔 두 청크를 보내고, 합쳐져 분석 대기로 넘어갈 때까지 기다린다
    @SuppressWarnings("unchecked")
    private JsonNode collectOn(String site) throws Exception {
        List<Map<String, Object>> chunks =
                objectMapper.readValue(
                        chunksJson.replace("https://example.com", site), new TypeReference<>() {});
        for (Map<String, Object> raw : chunks) {
            var body = new HashMap<>(raw);
            var payload = new HashMap<>((Map<String, Object>) body.get("payload"));
            payload.put("memberId", me.getId());
            body.put("payload", payload);
            mvc.perform(post("/sessions/chunks").with(as(me)).with(json(body)))
                    .andExpect(status().isCreated());
        }
        verify(aiClient, timeout(5000)).requestAnalysis(any());
        await().atMost(5, TimeUnit.SECONDS)
                .until(() -> row().getStatus() == SessionStatus.ANALYZING);
        return objectMapper.valueToTree(
                rawPayloads.findById(row().getId()).orElseThrow().getPayload());
    }

    private BrowsingSession row() {
        return sessions.findByUserIdAndCollectorSessionId(me.getId(), COLLECTOR).orElseThrow();
    }

    private Long addExcluded(String url) throws Exception {
        return readLong(
                mvc.perform(post(EXCLUDED_URLS).with(as(me)).with(json(Map.of("url", url))))
                        .andExpect(status().isCreated()),
                "$.data.excludedUrlId");
    }

    // session.visits · flushReport 의 pageId 들 (pageId 가 없는 항목은 뺀다)
    private static List<String> pageIdsIn(JsonNode bundle, String field) {
        var ids = new ArrayList<String>();
        for (JsonNode item : bundle.path("session").path(field)) {
            if (item.hasNonNull("pageId")) {
                ids.add(item.get("pageId").asString());
            }
        }
        return ids;
    }

    @Test
    @DisplayName(
            "[X-SET-02 S1·S5] naver.com 을 제외해 두면 www.naver.com 페이지가 저장된 원본에서 빠지고 그 focusMs · visits 도 빠진다")
    void excludedSitePageIsRemovedFromStoredBundle() throws Exception {
        addExcluded("naver.com");

        JsonNode bundle = collectOn("https://www.naver.com");

        assertThat(bundle.path("pages").size()).isZero();
        assertThat(bundle.path("session").path("focusMs").asLong()).isZero();
        // 뺀 페이지(pageId "…|a")의 visits · flushReport 항목도 빠진다
        assertThat(pageIdsIn(bundle, "visits")).doesNotContain(REMOVED_PAGE_ID);
        assertThat(pageIdsIn(bundle, "flushReport")).doesNotContain(REMOVED_PAGE_ID);
    }

    @Test
    @DisplayName("[X-SET-02 S2] naver.com 을 제외해도 하위 도메인 mail.naver.com 페이지는 원본에 남는다")
    void subdomainPageIsKept() throws Exception {
        addExcluded("naver.com");

        JsonNode bundle = collectOn("https://mail.naver.com");

        assertThat(bundle.path("pages").size()).isEqualTo(1);
        assertThat(bundle.path("pages").get(0).path("meta").path("url").asString())
                .isEqualTo("https://mail.naver.com/a");
        assertThat(bundle.path("session").path("focusMs").asLong()).isEqualTo(1200L);
    }

    @Test
    @DisplayName("[X-SET-02 S4] 설정 화면을 한 번도 안 연 회원도 기본 URL(instagram.com) 페이지는 원본에서 빠진다")
    void defaultUrlAppliesWithoutOpeningSettings() throws Exception {
        JsonNode bundle = collectOn("https://www.instagram.com");

        assertThat(bundle.path("pages").size()).isZero();
        assertThat(bundle.path("session").path("focusMs").asLong()).isZero();
    }

    @Test
    @DisplayName("[X-SET-02 R1] 내가 추가했다가 체크를 해제한 URL 의 페이지는 빼지 않는다")
    void uncheckedUrlIsNotApplied() throws Exception {
        Long naver = addExcluded("naver.com");
        mvc.perform(
                        patch(EXCLUDED_URLS + "/selections")
                                .with(as(me))
                                .with(
                                        json(
                                                Map.of(
                                                        "excludedUrlIds",
                                                        List.of(naver),
                                                        "isExcluded",
                                                        false))))
                .andExpect(okEnvelope());

        JsonNode bundle = collectOn("https://www.naver.com");

        assertThat(bundle.path("pages").size()).isEqualTo(1);
        assertThat(pageIdsIn(bundle, "visits")).contains(REMOVED_PAGE_ID);
    }
}
