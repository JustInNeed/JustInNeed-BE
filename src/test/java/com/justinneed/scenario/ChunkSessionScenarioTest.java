package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.client.AiAnalysisClient;
import com.justinneed.session.analysis.dto.AiAnalyzeRequest;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * 확장 청크 → 세션 → 합치기 → AI 요청 (확장 #34 · BE #116). 커밋 뒤 비동기로 나가는 AI 요청을 봐야 해서
 * 테스트 트랜잭션을 쓰지 않고 DB 를 따로 쓴다 (만든 행은 매번 지운다).
 * 청크 = src/test/resources/chunk-assembly/tricky.chunks.json (2개, 2번이 final). payload.memberId 만 이 테스트 회원으로 바꿔 보낸다.
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:chunk-session;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("확장 청크로 세션 만들기 · 합치기 · 분석 요청")
class ChunkSessionScenarioTest extends ScenarioTestSupport {

    private static final String COLLECTOR = "00000000-tric-ky00-0000-000000000001";
    private static final long START_TS = 1793600000000L;   // tricky 시작 기록의 ts

    @MockitoBean
    private AiAnalysisClient aiClient;

    @Autowired
    private SessionRawPayloadRepository rawPayloads;

    @Autowired
    private JdbcTemplate jdbc;

    private Member me;
    private List<Map<String, Object>> chunks;

    @BeforeEach
    void seed() throws Exception {
        me = member("chunky");
        try (InputStream in = getClass().getResourceAsStream("/chunk-assembly/tricky.chunks.json")) {
            chunks = objectMapper.readValue(in, new TypeReference<>() { });
        }
    }

    @AfterEach
    void cleanUp() {
        for (String table : List.of("session_raw_chunks", "summaries", "session_results", "session_raw_payloads", "sessions", "members")) {
            jdbc.update("delete from " + table);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> chunk(int n) {
        var body = new HashMap<>(chunks.get(n - 1));
        var payload = new HashMap<>((Map<String, Object>) body.get("payload"));
        payload.put("memberId", me.getId());
        body.put("payload", payload);
        return body;
    }

    private ResultActions send(Map<String, Object> body) throws Exception {
        return mvc.perform(post("/sessions/chunks").with(as(me)).with(json(body)));
    }

    private BrowsingSession collected() {
        return sessions.findByUserIdAndCollectorSessionId(me.getId(), COLLECTOR).orElseThrow();
    }

    private JsonNode expectedBundle() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/chunk-assembly/tricky.bundle.json")) {
            return objectMapper.readTree(in);
        }
    }

    @Test
    @DisplayName("마지막 청크가 먼저 오면 COLLECTING 으로 기다리고, 1번이 오면 합쳐 원본을 저장하고 AI 에 요청한다")
    void finalFirstThenComplete() throws Exception {
        send(chunk(2)).andExpect(status().isCreated());
        var waiting = collected();
        assertThat(waiting.getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(waiting.getTitle()).isEqualTo("수집 중인 세션");
        assertThat(rawPayloads.existsById(waiting.getId())).isFalse();
        verify(aiClient, after(300).never()).requestAnalysis(any());

        send(chunk(1)).andExpect(status().isCreated());
        var done = collected();
        assertThat(done.getId()).isEqualTo(waiting.getId());
        assertThat(done.getStatus()).isNotEqualTo(SessionStatus.COLLECTING);
        // 시작 시각은 1번 청크의 시작 기록으로 고쳐진다
        assertThat(done.getStartedAt())
                .isEqualTo(LocalDateTime.ofInstant(Instant.ofEpochMilli(START_TS), ZoneId.of("Asia/Seoul")));

        var captor = ArgumentCaptor.forClass(AiAnalyzeRequest.class);
        verify(aiClient, timeout(5000)).requestAnalysis(captor.capture());
        assertThat(captor.getValue().sessionId()).isEqualTo(done.getId());
        // AI 로 가는 원본 = 확장 assemble() 이 만든 정답과 같다
        JsonNode sent = objectMapper.readTree(objectMapper.writeValueAsString(captor.getValue().session()));
        assertThat(sent).isEqualTo(expectedBundle());
        assertThat(rawPayloads.findById(done.getId())).isPresent();
    }

    @Test
    @DisplayName("합친 뒤 같은 청크가 다시 와도 200 이고 AI 요청은 한 번뿐이다")
    void resendAfterCompleteDoesNothing() throws Exception {
        send(chunk(1)).andExpect(status().isCreated());
        assertThat(collected().getStatus()).isEqualTo(SessionStatus.COLLECTING);
        send(chunk(2)).andExpect(status().isCreated());
        verify(aiClient, timeout(5000)).requestAnalysis(any());

        send(chunk(2)).andExpect(status().isOk());
        send(chunk(1)).andExpect(status().isOk());
        verify(aiClient, after(500).times(1)).requestAnalysis(any());
        assertThat(sessions.findAll().stream().filter(s -> COLLECTOR.equals(s.getCollectorSessionId()))).hasSize(1);
    }

    @Test
    @DisplayName("청크가 다 왔어도 seq 가 끊기면 합치지 않고 COLLECTING 으로 둔다")
    @SuppressWarnings("unchecked")
    void brokenStaysCollecting() throws Exception {
        var second = chunk(2);
        var payload = (Map<String, Object>) second.get("payload");
        payload.put("fromSeq", ((Number) payload.get("fromSeq")).intValue() + 1);
        payload.put("records", ((List<Object>) payload.get("records")).subList(1, ((List<Object>) payload.get("records")).size()));

        send(chunk(1)).andExpect(status().isCreated());
        send(second).andExpect(status().isCreated());

        var session = collected();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(rawPayloads.existsById(session.getId())).isFalse();
        verify(aiClient, after(300).never()).requestAnalysis(any());
    }
}
