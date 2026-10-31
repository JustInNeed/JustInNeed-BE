package com.justinneed.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.justinneed.auth.domain.Member;
import com.justinneed.session.analysis.AiInternalToken;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 세션 행 잠금: 세션을 바꾸는 요청이 겹쳐도 앞선 저장이 사라지지 않고, 지운 세션이 되살아나지 않는다.
 * 다른 트랜잭션이 행을 잡은 동안 요청을 차례로 밀어 넣어, 잠금이 없으면 둘 다 옛 값을 읽게 만든다.
 * 실제로 커밋해야 하므로 테스트 트랜잭션을 쓰지 않고 DB 를 따로 쓴다 (만든 행은 매번 지운다).
 */
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:session-lock;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("세션 동시 저장")
class SessionLockScenarioTest extends ScenarioTestSupport {

    private static final String AI_TITLE = "AI 가 정한 제목";
    private static final String RESULT = """
            {"modelSource": "global", "scoreMethod": "C", "state": {"unitFeatures": []},
             "mindmap": {"structureSource": "llm",
                         "nodes": [{"id": "root:1", "type": "root", "keyword": "로마", "score": 1.0, "childCount": 0}],
                         "edges": []},
             "essay": {"source": "llm", "title": "AI 가 정한 제목", "sections": [{"heading": "소제목", "body": "본문"}]},
             "selected": [], "analysis": {"units": [], "summary": {}}, "pages": []}
            """;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SessionRawPayloadRepository rawPayloads;

    @Autowired
    private AiInternalToken internalToken;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);

    @AfterEach
    void cleanUp() {
        pool.shutdownNow();
        for (String table : List.of("summaries", "session_results", "session_raw_payloads", "sessions", "members")) {
            jdbc.update("delete from " + table);
        }
    }

    @Test
    @DisplayName("AI 결과와 즐겨찾기 저장이 겹쳐도 분석 결과와 즐겨찾기가 둘 다 남는다")
    void callbackAndFavourite() throws Exception {
        Member me = member("sprout");
        Long id = analyzing(me);

        race(id, callback(id), favourite(id, me));

        BrowsingSession session = reload(id);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getTitle()).isEqualTo(AI_TITLE);
        assertThat(session.isFavorite()).isTrue();
    }

    @Test
    @DisplayName("즐겨찾기 저장 뒤에 줄 선 AI 결과도 최신 행을 읽어 즐겨찾기를 덮지 않는다")
    void favouriteThenCallback() throws Exception {
        Member me = member("sprout");
        Long id = analyzing(me);

        race(id, favourite(id, me), callback(id));

        BrowsingSession session = reload(id);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.isFavorite()).isTrue();
    }

    @Test
    @DisplayName("즐겨찾기 저장 뒤에 줄 선 분석 실패도 최신 행을 읽어 즐겨찾기를 덮지 않는다")
    void favouriteThenFailure() throws Exception {
        Member me = member("sprout");
        Long id = analyzing(me);

        race(id, favourite(id, me), post("/internal/ai/sessions/{id}/failure", id)
                .header(AiInternalToken.HEADER, internalToken.value())
                .with(json("{\"reason\": \"x\"}")));

        BrowsingSession session = reload(id);
        assertThat(session.getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(session.isFavorite()).isTrue();
    }

    @Test
    @DisplayName("삭제와 즐겨찾기 저장이 겹쳐도 삭제가 되살아나지 않는다")
    void deleteAndFavourite() throws Exception {
        Member me = member("sprout");
        Long id = completed(me);

        race(id, delete("/sessions/{id}", id).with(as(me)), favourite(id, me));

        assertThat(isDeleted(id)).isTrue();
    }

    @Test
    @DisplayName("즐겨찾기 저장 뒤에 줄 선 삭제도 최신 행을 읽어 즐겨찾기를 덮지 않는다")
    void favouriteThenDelete() throws Exception {
        Member me = member("sprout");
        Long id = completed(me);

        race(id, favourite(id, me), delete("/sessions/{id}", id).with(as(me)));

        assertThat(isDeleted(id)).isTrue();
        assertThat(reload(id).isFavorite()).isTrue();
    }

    @Test
    @DisplayName("삭제와 AI 결과가 겹쳐도 삭제가 되살아나지 않는다")
    void deleteAndCallback() throws Exception {
        Member me = member("sprout");
        Long id = analyzing(me);

        race(id, delete("/sessions/{id}", id).with(as(me)), callback(id));

        assertThat(isDeleted(id)).isTrue();
    }

    @Test
    @DisplayName("태그 저장과 제목 · 본문 자동 저장이 겹쳐도 둘 다 남는다")
    void tagsAndAutosave() throws Exception {
        Member me = member("sprout");
        Long id = completed(me);

        race(id,
                patch("/sessions/{id}", id).with(as(me)).with(json(Map.of("tags", List.of("로마", "새태그")))),
                patch("/sessions/{id}", id).with(as(me)).with(json(Map.of("title", "자동 저장", "editedMarkdown", "본문"))));

        BrowsingSession session = reload(id);
        assertThat(session.getTags()).containsExactly("로마", "새태그");
        assertThat(session.getTitle()).isEqualTo("자동 저장");
        assertThat(session.getSummary().getMarkdown()).isEqualTo("본문");
    }

    @Test
    @DisplayName("요약이 없는 세션에 본문 저장이 겹쳐도 500 없이 둘 다 성공한다")
    void twoMarkdownSavesOnSessionWithoutSummary() throws Exception {
        Member me = member("sprout");
        Long id = completed(me);

        // 본문만 저장하면 sessions 행을 건드리지 않으므로, 커밋 전 요약 행으로 두 요청을 INSERT 앞에 세운다
        List<Integer> statuses = race(status -> {
                    jdbc.update("insert into summaries (session_id, markdown) values (?, '')", id);
                    status.setRollbackOnly();
                },
                patch("/sessions/{id}", id).with(as(me)).with(json(Map.of("editedMarkdown", "첫 번째"))),
                patch("/sessions/{id}", id).with(as(me)).with(json(Map.of("editedMarkdown", "두 번째"))));

        assertThat(statuses).containsExactly(200, 200);
        assertThat(reload(id).getSummary().getMarkdown()).isEqualTo("두 번째");
    }

    private List<Integer> race(Long id, MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second)
            throws Exception {
        return race(status -> jdbc.queryForObject("select id from sessions where id = ? for update", Long.class, id),
                first, second);
    }

    // 다른 트랜잭션이 붙잡고 있는 동안 요청을 순서대로 보내 둘 다 앞에 세운 뒤 놓는다. 응답 코드를 보낸 순서대로 돌려준다
    private List<Integer> race(Consumer<TransactionStatus> hold, MockHttpServletRequestBuilder first,
            MockHttpServletRequestBuilder second) throws Exception {
        List<Future<Integer>> responses = new ArrayList<>();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            hold.accept(status);
            responses.add(send(first));
            pause();
            responses.add(send(second));
            pause();
        });
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> response : responses) {
            statuses.add(response.get(20, TimeUnit.SECONDS));
        }
        return statuses;
    }

    private Future<Integer> send(MockHttpServletRequestBuilder request) {
        return pool.submit(() -> mvc.perform(request).andReturn().getResponse().getStatus());
    }

    private static void pause() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private MockHttpServletRequestBuilder callback(Long id) {
        return post("/internal/ai/sessions/{id}/result", id)
                .header(AiInternalToken.HEADER, internalToken.value())
                .with(json(RESULT));
    }

    private MockHttpServletRequestBuilder favourite(Long id, Member me) {
        return patch("/sessions/{id}", id).with(as(me)).with(json(Map.of("isFavorite", true)));
    }

    private Long analyzing(Member owner) {
        Long id = sessionWithStatus(owner, "분석 중", "2026-10-30T09:00:00", "2026-10-30T10:00:00",
                SessionStatus.ANALYZING, List.of()).getId();
        rawPayloads.save(new SessionRawPayload(id, owner.getId(), Map.of("pages", List.of()), LocalDateTime.now()));
        return id;
    }

    private Long completed(Member owner) {
        return session(owner, "완료", "2026-10-30T09:00:00", "2026-10-30T10:00:00", 1, false, false,
                List.of("로마"), List.of(), null).getId();
    }

    private BrowsingSession reload(Long id) {
        return sessions.findById(id).orElseThrow();
    }

    private boolean isDeleted(Long id) {
        return jdbc.queryForObject("select deleted_at is not null from sessions where id = ?", Boolean.class, id);
    }
}
