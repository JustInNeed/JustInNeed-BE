package com.justinneed.session.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
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
    private static final String CHUNK = "chunk-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionRawChunkRepository chunkRepository;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private SessionCollectionRepository collectionRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        chunkRepository.deleteAll();
        collectionRepository.deleteAll();
        sessionRepository.deleteAll();
    }

    private Map<String, Object> chunk(String chunkId) {
        var body = new HashMap<String, Object>();
        body.put("sessionId", SESSION);
        body.put("chunkId", chunkId);
        body.put("payload", Map.of("pages", List.of(Map.of("timeline", List.of(Map.of("t", 1200))))));
        return body;
    }

    private ResultActions send(Long userId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/sessions/chunks")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private SessionRawChunk stored(Long userId, String chunkId) {
        return chunkRepository.findByUserIdAndCollectorSessionIdAndChunkId(userId, SESSION, chunkId).orElseThrow();
    }

    private BrowsingSession session(Long userId) {
        return sessionRepository.findByUserIdAndCollectorSessionId(userId, SESSION).orElseThrow();
    }

    @Test
    @DisplayName("처음 받은 청크는 저장하고 201 · duplicate false")
    void firstChunkIsStored() throws Exception {
        send(USER_ID, chunk(CHUNK))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION))
                .andExpect(jsonPath("$.data.chunkId").value(CHUNK))
                .andExpect(jsonPath("$.data.duplicate").value(false))
                .andExpect(jsonPath("$.data.ignored").value(false))
                .andExpect(jsonPath("$.data.receivedAt").isNotEmpty());

        assertThat(stored(USER_ID, CHUNK).getPayload()).containsKey("pages");
    }

    @Test
    @DisplayName("같은 청크를 다시 보내면 저장하지 않고 200 · duplicate true, 처음 받은 내용 · 시각을 그대로 둔다")
    void resentChunkIsIgnored() throws Exception {
        send(USER_ID, chunk(CHUNK)).andExpect(status().isCreated());
        var first = stored(USER_ID, CHUNK);

        var resent = chunk(CHUNK);
        resent.put("payload", Map.of("pages", List.of()));
        send(USER_ID, resent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true))
                .andExpect(jsonPath("$.data.chunkId").value(CHUNK));

        assertThat(chunkRepository.count()).isEqualTo(1);
        var kept = stored(USER_ID, CHUNK);
        assertThat(kept.getPayload()).isEqualTo(first.getPayload());
        assertThat(kept.getReceivedAt()).isEqualTo(first.getReceivedAt());
    }

    @Test
    @DisplayName("청크 id 가 다르면 다른 청크로 저장하고, 세션은 하나다")
    void differentChunkIdIsNewChunk() throws Exception {
        send(USER_ID, chunk("a")).andExpect(status().isCreated());
        send(USER_ID, chunk("b")).andExpect(status().isCreated());

        assertThat(chunkRepository.count()).isEqualTo(2);
        assertThat(sessionRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 회원이 같은 세션 id · 청크 id 로 보내도 그 회원의 세션 · 청크로 따로 저장한다")
    void sameKeyFromAnotherUserIsSeparate() throws Exception {
        send(USER_ID, chunk(CHUNK)).andExpect(status().isCreated());
        send(OTHER_USER_ID, chunk(CHUNK))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.duplicate").value(false));

        assertThat(chunkRepository.count()).isEqualTo(2);
        assertThat(session(USER_ID).getId()).isNotEqualTo(session(OTHER_USER_ID).getId());
    }

    @Test
    @DisplayName("시작 요청 없이 온 청크는 세션을 만들고, 청크의 공통 값이 있으면 그 시작 시각을 쓴다")
    void firstChunkCreatesSession() throws Exception {
        var body = chunk(CHUNK);
        body.put("payload", Map.of("session", Map.of("startedAt", "2026-10-30T16:44:04Z")));

        send(USER_ID, body).andExpect(status().isCreated());

        BrowsingSession session = session(USER_ID);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COLLECTING);
        assertThat(session.getStartedAt()).isEqualTo(LocalDateTime.of(2026, 10, 31, 1, 44, 4));
        assertThat(session.getTitle()).endsWith("의 1번째 세션");
    }

    @Test
    @DisplayName("청크를 받으면 마지막 청크 시각을 갱신한다")
    void lastChunkAtIsUpdated() throws Exception {
        send(USER_ID, chunk(CHUNK)).andExpect(status().isCreated());

        assertThat(collectionRepository.findById(session(USER_ID).getId()).orElseThrow().getLastChunkAt())
                .isEqualTo(stored(USER_ID, CHUNK).getReceivedAt());
    }

    @Test
    @DisplayName("지운 세션의 청크는 저장하지 않고 200 · ignored true")
    void deletedSessionChunkIsDropped() throws Exception {
        send(USER_ID, chunk("a")).andExpect(status().isCreated());
        BrowsingSession session = session(USER_ID);
        session.delete();
        sessionRepository.save(session);

        send(USER_ID, chunk("b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ignored").value(true))
                .andExpect(jsonPath("$.data.duplicate").value(false));

        assertThat(chunkRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("수집이 끝난 세션의 늦은 청크는 저장하지 않고 200 · ignored true")
    void lateChunkIsDropped() throws Exception {
        send(USER_ID, chunk("a")).andExpect(status().isCreated());
        BrowsingSession session = session(USER_ID);
        session.markReady();
        sessionRepository.save(session);

        send(USER_ID, chunk("b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ignored").value(true));

        assertThat(chunkRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("세션 id · 청크 id · payload 가 없으면 400")
    void missingOrInvalidFieldsAreRejected() throws Exception {
        var noSession = chunk(CHUNK);
        noSession.remove("sessionId");
        send(USER_ID, noSession)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("세션 id 를 입력해 주세요."));

        send(USER_ID, chunk(" "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 id 를 입력해 주세요."));

        var noPayload = chunk(CHUNK);
        noPayload.remove("payload");
        send(USER_ID, noPayload)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("청크 내용(payload)을 입력해 주세요."));

        assertThat(chunkRepository.count()).isZero();
        assertThat(sessionRepository.count()).isZero();
    }

    @Test
    @DisplayName("payload 가 JSON 객체가 아니면 400")
    void nonObjectPayloadIsRejected() throws Exception {
        var body = chunk(CHUNK);
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
                        .content(objectMapper.writeValueAsString(chunk(CHUNK))))
                .andExpect(status().isUnauthorized());
        assertThat(chunkRepository.count()).isZero();
    }

    @Test
    @DisplayName("DB 유니크 키가 (회원, 세션 id, 청크 id) 중복을 막는다")
    void uniqueKeyRejectsDuplicateRow() {
        chunkRepository.saveAndFlush(new SessionRawChunk(USER_ID, SESSION, CHUNK, Map.of("a", 1), LocalDateTime.now()));
        assertThatThrownBy(() -> chunkRepository.saveAndFlush(
                new SessionRawChunk(USER_ID, SESSION, CHUNK, Map.of("b", 2), LocalDateTime.now())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
