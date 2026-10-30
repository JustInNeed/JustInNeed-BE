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
    private static final String SEG = "4d552ca4-4a1c-4ebc-b314-1108b1eadd45";

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

    private Map<String, Object> chunk(String segId, int n) {
        var body = new HashMap<String, Object>();
        body.put("sessionId", SESSION);
        body.put("segId", segId);
        body.put("n", n);
        body.put("payload", Map.of("records", List.of(Map.of("t", 1200, "scrollY", 340))));
        return body;
    }

    private ResultActions send(Long userId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/sessions/chunks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private SessionRawChunk stored(Long userId, String segId, int n) {
        return chunkRepository.findByUserIdAndCollectorSessionIdAndSegIdAndN(userId, SESSION, segId, n).orElseThrow();
    }

    @Test
    @DisplayName("처음 받은 청크는 저장하고 201 · duplicate false")
    void firstChunkIsStored() throws Exception {
        send(USER_ID, chunk(SEG, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION))
                .andExpect(jsonPath("$.data.segId").value(SEG))
                .andExpect(jsonPath("$.data.n").value(1))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.receivedAt").isNotEmpty());

        var saved = stored(USER_ID, SEG, 1);
        assertThat(saved.isFinalChunk()).isFalse();
        assertThat(saved.getMaxN()).isNull();
        assertThat(saved.getPayload()).containsKey("records");
    }

    @Test
    @DisplayName("같은 청크를 다시 보내면 저장하지 않고 200 · duplicate true, 처음 받은 내용 · 시각을 그대로 둔다")
    void resentChunkIsIgnored() throws Exception {
        send(USER_ID, chunk(SEG, 1)).andExpect(status().isCreated());
        var first = stored(USER_ID, SEG, 1);

        var resent = chunk(SEG, 1);
        resent.put("payload", Map.of("records", List.of()));
        send(USER_ID, resent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.n").value(1));

        assertThat(chunkRepository.count()).isEqualTo(1);
        var kept = stored(USER_ID, SEG, 1);
        assertThat(kept.getPayload()).isEqualTo(first.getPayload());
        assertThat(kept.getReceivedAt()).isEqualTo(first.getReceivedAt());
    }

    @Test
    @DisplayName("청크 번호나 구간이 다르면 다른 청크로 저장한다")
    void differentNumberOrSegmentIsNewChunk() throws Exception {
        send(USER_ID, chunk(SEG, 1)).andExpect(status().isCreated());
        send(USER_ID, chunk(SEG, 2)).andExpect(status().isCreated());
        send(USER_ID, chunk("other-seg", 1)).andExpect(status().isCreated());
        assertThat(chunkRepository.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("다른 회원이 같은 세션 id · 구간 · 번호로 보내도 그 회원의 청크로 따로 저장한다")
    void sameKeyFromAnotherUserIsSeparate() throws Exception {
        send(USER_ID, chunk(SEG, 1)).andExpect(status().isCreated());
        send(OTHER_USER_ID, chunk(SEG, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(false));
        assertThat(chunkRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("마지막 청크는 final · maxN · stopReason 을 함께 저장한다")
    void finalChunkKeepsEndInfo() throws Exception {
        var last = chunk(SEG, 62);
        last.put("final", true);
        last.put("maxN", 62);
        last.put("stopReason", "user");
        send(USER_ID, last).andExpect(status().isCreated());

        var saved = stored(USER_ID, SEG, 62);
        assertThat(saved.isFinalChunk()).isTrue();
        assertThat(saved.getMaxN()).isEqualTo(62);
        assertThat(saved.getStopReason()).isEqualTo("user");
    }

    @Test
    @DisplayName("final 이 false 로 오면 마지막 청크가 아니다")
    void explicitNonFinal() throws Exception {
        var body = chunk(SEG, 3);
        body.put("final", false);
        send(USER_ID, body).andExpect(status().isCreated());
        assertThat(stored(USER_ID, SEG, 3).isFinalChunk()).isFalse();
    }

    @Test
    @DisplayName("마지막 청크인데 maxN 이 없으면 400")
    void finalWithoutMaxNIsRejected() throws Exception {
        var body = chunk(SEG, 5);
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
        var body = chunk(SEG, 63);
        body.put("final", true);
        body.put("maxN", 62);
        send(USER_ID, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 번호(n)는 총 청크 수(maxN)보다 클 수 없습니다."));
    }

    @Test
    @DisplayName("청크 번호가 1보다 작거나, 세션 id · 구간 id · payload 가 없으면 400")
    void missingOrInvalidFieldsAreRejected() throws Exception {
        send(USER_ID, chunk(SEG, 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 번호(n)는 1 이상이어야 합니다."));

        var noSession = chunk(SEG, 1);
        noSession.remove("sessionId");
        send(USER_ID, noSession)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("세션 id 를 입력해 주세요."));

        var noSeg = chunk(" ", 1);
        send(USER_ID, noSeg)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("구간 id(segId)를 입력해 주세요."));

        var noPayload = chunk(SEG, 1);
        noPayload.remove("payload");
        send(USER_ID, noPayload)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 내용(payload)을 입력해 주세요."));

        assertThat(chunkRepository.count()).isZero();
    }

    @Test
    @DisplayName("payload 가 JSON 객체가 아니면 400")
    void nonObjectPayloadIsRejected() throws Exception {
        var body = chunk(SEG, 1);
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
                        .content(objectMapper.writeValueAsString(chunk(SEG, 1))))
                .andExpect(status().isUnauthorized());
        assertThat(chunkRepository.count()).isZero();
    }

    @Test
    @DisplayName("DB 유니크 키가 (회원, 세션 id, 구간, 번호) 중복을 막는다 — 동시에 같은 청크가 들어와도 하나만 남는다")
    void uniqueKeyRejectsDuplicateRow() {
        chunkRepository.saveAndFlush(new SessionRawChunk(USER_ID, SESSION, SEG, 1, false, null, null,
                Map.of("a", 1), LocalDateTime.now()));
        assertThatThrownBy(() -> chunkRepository.saveAndFlush(new SessionRawChunk(USER_ID, SESSION, SEG, 1, false, null, null,
                Map.of("b", 2), LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
