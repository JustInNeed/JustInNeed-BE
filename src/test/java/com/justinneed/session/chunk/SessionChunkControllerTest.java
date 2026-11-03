package com.justinneed.session.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("세션 원본 청크 수신 (POST /sessions/chunks)")
class SessionChunkControllerTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final String SESSION = "1731a8f1-668b-4efe-b524-ff9a13913058";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionRawChunkRepository chunkRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
    }

    private Map<String, Object> chunk(String sessionId, int n) {
        var body = new HashMap<String, Object>();
        body.put("sessionId", sessionId);
        body.put("n", n);
        body.put("payload", Map.of("records", List.of(Map.of("t", 1200, "scrollY", 340))));
        return body;
    }

    // payload.memberId 가 없으면 보내는 회원 id 를 넣는다 (확장은 늘 넣는다). 일부러 빼거나 다르게 할 때는 테스트가 직접 넣는다
    @SuppressWarnings("unchecked")
    private ResultActions send(Long userId, Map<String, Object> body) throws Exception {
        if (body.get("payload") instanceof Map<?, ?> p && !p.containsKey("memberId") && !body.containsKey("noMember")) {
            var withMember = new HashMap<String, Object>((Map<String, Object>) p);
            withMember.put("memberId", userId);
            body.put("payload", withMember);
        }
        body.remove("noMember");
        return mockMvc.perform(post("/sessions/chunks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private SessionRawChunk stored(Long userId, int n) {
        return chunkRepository.findByUserIdAndCollectorSessionIdAndN(userId, SESSION, n).orElseThrow();
    }

    @Test
    @DisplayName("처음 받은 청크는 저장하고 201 · duplicate false")
    void firstChunkIsStored() throws Exception {
        send(USER_ID, chunk(SESSION, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION))
                .andExpect(jsonPath("$.data.n").value(1))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.receivedAt").isNotEmpty());

        var saved = stored(USER_ID, 1);
        assertThat(saved.isFinalChunk()).isFalse();
        assertThat(saved.getMaxN()).isNull();
        assertThat(saved.getPayload()).containsKey("records");
    }

    @Test
    @DisplayName("같은 청크를 같은 내용으로 다시 보내면 저장하지 않고 200 · duplicate true, 처음 받은 시각을 그대로 둔다")
    void resentChunkIsIgnored() throws Exception {
        send(USER_ID, chunk(SESSION, 1)).andExpect(status().isCreated());
        var first = stored(USER_ID, 1);

        send(USER_ID, chunk(SESSION, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.n").value(1));

        assertThat(chunkRepository.count()).isEqualTo(1);
        assertThat(stored(USER_ID, 1).getReceivedAt()).isEqualTo(first.getReceivedAt());
    }

    @Test
    @DisplayName("같은 번호인데 내용이 다르면 409, 처음 받은 내용을 그대로 둔다 — 확장이 지우지 않게")
    void sameNumberDifferentContentIsConflict() throws Exception {
        send(USER_ID, chunk(SESSION, 1)).andExpect(status().isCreated());
        var first = stored(USER_ID, 1);

        var changed = chunk(SESSION, 1);
        changed.put("payload", Map.of("records", List.of(Map.of("t", 1200, "scrollY", 340), Map.of("t", 1350))));
        send(USER_ID, changed)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("같은 번호의 청크가 다른 내용으로 이미 저장돼 있습니다."));

        var nowFinal = chunk(SESSION, 1);
        nowFinal.put("final", true);
        nowFinal.put("maxN", 1);
        nowFinal.put("stopReason", "user");
        send(USER_ID, nowFinal).andExpect(status().isConflict());

        assertThat(chunkRepository.count()).isEqualTo(1);
        assertThat(stored(USER_ID, 1).getPayload()).isEqualTo(first.getPayload());
    }

    @Test
    @DisplayName("payload.memberId 가 없거나 숫자가 아니면 400, 로그인한 회원과 다르면 403 — 저장하지 않는다")
    void memberIdMustMatchToken() throws Exception {
        var none = chunk(SESSION, 1);
        none.put("noMember", true);
        send(USER_ID, none)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크에 회원 id(payload.memberId)가 없습니다."));

        var text = chunk(SESSION, 1);
        text.put("payload", Map.of("memberId", "1", "records", List.of()));
        send(USER_ID, text).andExpect(status().isBadRequest());

        var other = chunk(SESSION, 1);
        other.put("payload", Map.of("memberId", OTHER_USER_ID, "records", List.of()));
        send(USER_ID, other)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("청크의 회원 id(payload.memberId)가 로그인한 회원과 다릅니다."));

        assertThat(chunkRepository.count()).isZero();
    }

    @Test
    @DisplayName("청크 번호나 세션 id 가 다르면 다른 청크로 저장한다")
    void differentNumberOrSessionIsNewChunk() throws Exception {
        send(USER_ID, chunk(SESSION, 1)).andExpect(status().isCreated());
        send(USER_ID, chunk(SESSION, 2)).andExpect(status().isCreated());
        send(USER_ID, chunk("other-session", 1)).andExpect(status().isCreated());
        assertThat(chunkRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 회원이 같은 세션 id · 번호로 보내도 그 회원의 청크로 따로 저장한다")
    void sameKeyFromAnotherUserIsSeparate() throws Exception {
        send(USER_ID, chunk(SESSION, 1)).andExpect(status().isCreated());
        send(OTHER_USER_ID, chunk(SESSION, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(false));
        assertThat(chunkRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("옛 칸 segId 가 같이 와도 무시하고 받는다 (세션 id · 번호가 같으면 같은 청크)")
    void legacySegIdIsIgnored() throws Exception {
        var withSeg = chunk(SESSION, 1);
        withSeg.put("segId", "4d552ca4-4a1c-4ebc-b314-1108b1eadd45");
        send(USER_ID, withSeg).andExpect(status().isCreated());

        var otherSeg = chunk(SESSION, 1);
        otherSeg.put("segId", "other-seg");
        send(USER_ID, otherSeg)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.segId").doesNotExist());
        assertThat(chunkRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 청크는 final · maxN · stopReason 을 함께 저장한다")
    void finalChunkKeepsEndInfo() throws Exception {
        var last = chunk(SESSION, 62);
        last.put("final", true);
        last.put("maxN", 62);
        last.put("stopReason", "user");
        send(USER_ID, last).andExpect(status().isCreated());

        var saved = stored(USER_ID, 62);
        assertThat(saved.isFinalChunk()).isTrue();
        assertThat(saved.getMaxN()).isEqualTo(62);
        assertThat(saved.getStopReason()).isEqualTo("user");
    }

    @Test
    @DisplayName("final 이 false 로 오면 마지막 청크가 아니다")
    void explicitNonFinal() throws Exception {
        var body = chunk(SESSION, 3);
        body.put("final", false);
        send(USER_ID, body).andExpect(status().isCreated());
        assertThat(stored(USER_ID, 3).isFinalChunk()).isFalse();
    }

    @Test
    @DisplayName("마지막 청크인데 maxN 이 없으면 400")
    void finalWithoutMaxNIsRejected() throws Exception {
        var body = chunk(SESSION, 5);
        body.put("final", true);
        send(USER_ID, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("마지막 청크에는 총 청크 수(maxN)가 필요합니다."));
        assertThat(chunkRepository.count()).isZero();
    }

    @Test
    @DisplayName("청크 번호가 maxN 보다 크면 400")
    void numberOverMaxNIsRejected() throws Exception {
        var body = chunk(SESSION, 63);
        body.put("final", true);
        body.put("maxN", 62);
        send(USER_ID, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 번호(n)는 총 청크 수(maxN)보다 클 수 없습니다."));
    }

    @Test
    @DisplayName("청크 번호가 1보다 작거나, 세션 id · payload 가 없으면 400")
    void missingOrInvalidFieldsAreRejected() throws Exception {
        send(USER_ID, chunk(SESSION, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 번호(n)는 1 이상이어야 합니다."));

        var noSession = chunk(SESSION, 1);
        noSession.remove("sessionId");
        send(USER_ID, noSession)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("세션 id 를 입력해 주세요."));

        var noPayload = chunk(SESSION, 1);
        noPayload.remove("payload");
        send(USER_ID, noPayload)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 내용(payload)을 입력해 주세요."));

        assertThat(chunkRepository.count()).isZero();
    }

    @Test
    @DisplayName("payload 가 JSON 객체가 아니면 400")
    void nonObjectPayloadIsRejected() throws Exception {
        var body = chunk(SESSION, 1);
        body.put("payload", List.of(1, 2, 3));
        send(USER_ID, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."));
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(post("/sessions/chunks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(chunk(SESSION, 1))))
                .andExpect(status().isUnauthorized());
        assertThat(chunkRepository.count()).isZero();
    }

    @Test
    @DisplayName("DB 유니크 키가 (회원, 세션 id, 번호) 중복을 막는다 — 동시에 같은 청크가 들어와도 하나만 남는다")
    void uniqueKeyRejectsDuplicateRow() {
        chunkRepository.saveAndFlush(new SessionRawChunk(USER_ID, SESSION, 1, false, null, null,
                Map.of("a", 1), LocalDateTime.now()));
        assertThatThrownBy(() -> chunkRepository.saveAndFlush(new SessionRawChunk(USER_ID, SESSION, 1, false, null, null,
                Map.of("b", 2), LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
