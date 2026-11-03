package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.justinneed.session.analysis.domain.AiClusterModel;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.analysis.service.AnalysisFailures;
import com.justinneed.session.analysis.service.RawPayloadCleanupScheduler;
import com.justinneed.session.chunk.domain.SessionRawChunk;
import com.justinneed.session.chunk.repository.SessionRawChunkRepository;
import com.justinneed.session.collection.domain.SessionCollection;
import com.justinneed.session.collection.repository.SessionCollectionRepository;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import com.justinneed.session.summary.domain.Summary;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisSchemaTest {

    private static final Long USER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 30, 12, 0);

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SessionResultRepository resultRepository;

    @Autowired
    private SessionRawPayloadRepository rawRepository;

    @Autowired
    private AiClusterModelRepository modelRepository;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private RawPayloadCleanupScheduler cleanupScheduler;

    @Autowired
    private SessionRawChunkRepository chunkRepository;

    @Autowired
    private SessionCollectionRepository collectionRepository;

    @Autowired
    private AnalysisFailures failures;

    // AI 출력(pipeline/output.py)과 같은 모양
    private static final Map<String, Object> MINDMAP = Map.of(
            "rootSource", "llm",
            "structureSource", "llm",
            "repairs", List.of(),
            "nodes", List.of(
                    Map.of("id", "root:1", "type", "root", "keyword", "고양이 돌보기", "score", 3.9, "childCount", 1),
                    Map.of("id", "topic:0", "type", "topic", "keyword", "음수량", "score", 3.9, "childCount", 1),
                    Map.of("id", "https://cat.example.com#u1", "type", "unit", "keyword", "권장량", "score", 3.9,
                            "childCount", 0, "unitRef", Map.of("pageId", "https://cat.example.com", "pid", "u1"),
                            "mergedFrom", List.of())),
            "edges", List.of(
                    Map.of("from", "root:1", "to", "topic:0", "type", "hierarchy", "primary", true, "order", 0),
                    Map.of("from", "topic:0", "to", "https://cat.example.com#u1", "type", "hierarchy",
                            "primary", true, "order", 0)));

    private static final Map<String, Object> UNIT = Map.of(
            "unitKey", "https://cat.example.com#u1",
            "pageTitle", "고양이 물 마시기",
            "pageUrl", "https://cat.example.com",
            "text", "고양이 음수량은 체중 1kg 당 하루 약 50ml 가 권장된다.",
            "clusterLabel", "Deep Reading",
            "score", 3.9,
            "dwellMs", 4200,
            "firstSeenAt", "2026-10-30T09:00:01+00:00",
            "mergedFrom", List.of());

    @Test
    void 세션_결과는_AI_출력_JSON_을_그대로_저장하고_읽는다() {
        SessionResult result = new SessionResult(10L, USER_ID);
        result.replace(
                MINDMAP,
                List.of(UNIT),
                List.of(Map.of("unitKey", "https://cat.example.com#u1", "clusterLabel", "Deep Reading",
                        "dwellMs", 4200, "selected", true, "mergedInto", "")),
                Map.of("Idle", Map.of("units", 2, "dwellMs", 0), "Deep Reading", Map.of("units", 1, "dwellMs", 4200)),
                List.of(Map.of("url", "https://cat.example.com", "title", "고양이 물 마시기",
                        "category", "블로그", "dwellMs", 200000)),
                List.of(Map.of("pid", "u1", "dwell_ms", 4200, "cursor_speed_z", 0.31)),
                "global",
                "A");
        resultRepository.save(result);
        flushAndClear();

        SessionResult found = resultRepository.findById(10L).orElseThrow();
        assertThat(found.getMindmap()).isEqualTo(MINDMAP);
        assertThat(found.getSelectedUnits()).containsExactly(UNIT);
        assertThat(found.getAnalysisSummary()).containsKey("Deep Reading");
        assertThat(found.getPages()).singleElement().extracting(p -> p.get("category")).isEqualTo("블로그");
        assertThat(found.getUnitFeatures()).singleElement().extracting(f -> f.get("cursor_speed_z")).isEqualTo(0.31);
        assertThat(found.getModelSource()).isEqualTo("global");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(resultRepository.findActiveByUserId(USER_ID)).hasSize(1);
        assertThat(resultRepository.countByUserId(USER_ID)).isEqualTo(1);
    }

    @Test
    void LLM_없이_분석한_결과는_마인드맵이_없어도_저장된다() {
        SessionResult result = new SessionResult(11L, USER_ID);
        resultRepository.save(result);
        flushAndClear();

        SessionResult found = resultRepository.findById(11L).orElseThrow();
        assertThat(found.getMindmap()).isNull();
        assertThat(found.getSelectedUnits()).isEmpty();
        assertThat(found.getAnalysisSummary()).isEmpty();
    }

    @Test
    void 원본은_분석_완료_후_21일이_지나면_삭제_대상이다() {
        Map<String, Object> payload = Map.of("kind", "rbc-session", "session", Map.of("sessionId", "abc"),
                "pages", List.of(Map.of("timeline", List.of(Map.of("type", "tick", "t", 150)))));
        SessionRawPayload expired = new SessionRawPayload(20L, USER_ID, payload, NOW.minusDays(30));
        expired.markAnalyzed(NOW.minusDays(22));
        SessionRawPayload kept = new SessionRawPayload(21L, USER_ID, payload, NOW.minusDays(5));
        kept.markAnalyzed(NOW.minusDays(5));
        SessionRawPayload notAnalyzed = new SessionRawPayload(22L, USER_ID, payload, NOW.minusDays(40));
        rawRepository.saveAll(List.of(expired, kept, notAnalyzed));
        flushAndClear();

        assertThat(rawRepository.findById(21L).orElseThrow().getExpiresAt()).isEqualTo(NOW.plusDays(16));
        assertThat(rawRepository.findById(21L).orElseThrow().getPayload()).isEqualTo(payload);

        int deleted = rawRepository.deleteExpired(NOW);

        assertThat(deleted).isEqualTo(1);
        assertThat(rawRepository.findAll()).extracting(SessionRawPayload::getSessionId)
                .containsExactlyInAnyOrder(21L, 22L);
    }

    @Test
    void 정리_스케줄러는_보관_기한이_지난_원본만_지운다() {
        Map<String, Object> payload = Map.of("kind", "rbc-session");
        SessionRawPayload expired = new SessionRawPayload(30L, USER_ID, payload, LocalDateTime.now().minusDays(40));
        expired.markAnalyzed(LocalDateTime.now().minusDays(22));
        SessionRawPayload kept = new SessionRawPayload(31L, USER_ID, payload, LocalDateTime.now().minusDays(3));
        kept.markAnalyzed(LocalDateTime.now().minusDays(3));
        rawRepository.saveAll(List.of(expired, kept));
        flushAndClear();

        cleanupScheduler.purgeExpiredPayloads();

        assertThat(rawRepository.findAll()).extracting(SessionRawPayload::getSessionId).containsExactly(31L);
    }

    @Test
    void 실패하면_원본은_실패_후_21일_뒤_만료되고_재분석하면_만료를_해제한다() {
        SessionRawPayload raw = new SessionRawPayload(40L, USER_ID, Map.of("kind", "rbc-session"), NOW);

        raw.markFailed(NOW);
        assertThat(raw.getExpiresAt()).isEqualTo(NOW.plusDays(21));

        raw.clearExpiry();
        assertThat(raw.getExpiresAt()).isNull();
    }

    @Test
    void 정리_스케줄러는_실패한_지_21일이_지난_세션의_청크만_지운다() {
        LocalDateTime now = LocalDateTime.now();
        BrowsingSession expired = failedSession("expired", now.minusDays(22));
        BrowsingSession recent = failedSession("recent", now.minusDays(3));
        BrowsingSession collecting = sessionRepository.save(new BrowsingSession(USER_ID, "수집 중", NOW, "collecting"));
        for (BrowsingSession session : List.of(expired, recent, collecting)) {
            chunkRepository.save(new SessionRawChunk(USER_ID, session.getCollectorSessionId(), 1, false, null, null,
                    Map.of("records", List.of()), now));
        }
        // 같은 수집기 세션 id 라도 다른 회원의 청크는 그대로 둔다
        chunkRepository.save(new SessionRawChunk(2L, "expired", 1, false, null, null,
                    Map.of("records", List.of()), now));
        flushAndClear();

        cleanupScheduler.purgeExpiredPayloads();

        assertThat(chunkRepository.findAll()).extracting(SessionRawChunk::getCollectorSessionId, SessionRawChunk::getUserId)
                .containsExactlyInAnyOrder(
                        tuple("recent", USER_ID),
                        tuple("collecting", USER_ID),
                        tuple("expired", 2L));
    }

    @Test
    void 실패를_거치면_실패_시각과_원본_만료가_함께_남는다() {
        BrowsingSession session = sessionRepository.save(new BrowsingSession(USER_ID, "세션", NOW, "fail"));
        rawRepository.save(new SessionRawPayload(session.getId(), USER_ID, Map.of("kind", "rbc-session"), NOW));

        failures.fail(session);
        flushAndClear();

        SessionCollection collection = collectionRepository.findById(session.getId()).orElseThrow();
        assertThat(collection.getFailedAt()).isNotNull();
        assertThat(rawRepository.findById(session.getId()).orElseThrow().getExpiresAt())
                .isEqualTo(collection.getFailedAt().plusDays(21));
    }

    @Test
    void 같은_사용자의_수집기_세션_id_는_하나만_둘_수_있다() {
        sessionRepository.save(new BrowsingSession(USER_ID, "세션", NOW, "same"));
        sessionRepository.save(new BrowsingSession(2L, "다른 회원 세션", NOW, "same"));
        sessionRepository.save(new BrowsingSession(USER_ID, "수집기 없는 세션", NOW));
        sessionRepository.save(new BrowsingSession(USER_ID, "수집기 없는 세션", NOW));
        sessionRepository.flush();

        assertThatThrownBy(() -> {
            sessionRepository.save(new BrowsingSession(USER_ID, "세션", NOW, "same"));
            sessionRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 군집_모델은_전체_1개와_사용자별로_저장된다() {
        Map<String, Object> model = Map.of("scope", "global", "centroids", List.of(List.of(0.1, 0.2), List.of(1.0, 2.0)),
                "levels", List.of(0, 3, 1, 2));
        modelRepository.save(new AiClusterModel(ClusterModelScope.GLOBAL, 99L, model, 40, NOW));
        modelRepository.save(new AiClusterModel(ClusterModelScope.USER, USER_ID, model, 12, NOW));
        flushAndClear();

        AiClusterModel global = modelRepository.findFirstByScope(ClusterModelScope.GLOBAL).orElseThrow();
        assertThat(global.getUserId()).isNull();
        assertThat(global.getModel()).isEqualTo(model);

        AiClusterModel user = modelRepository.findByScopeAndUserId(ClusterModelScope.USER, USER_ID).orElseThrow();
        user.replace(Map.of("levels", List.of(1, 0, 3, 2)), 13, NOW.plusDays(1));
        flushAndClear();
        assertThat(modelRepository.findByScopeAndUserId(ClusterModelScope.USER, USER_ID).orElseThrow()
                .getSessionCount()).isEqualTo(13);
    }

    @Test
    void 같은_사용자의_개인_모델은_하나만_둘_수_있다() {
        modelRepository.save(new AiClusterModel(ClusterModelScope.USER, USER_ID, Map.of(), 10, NOW));

        // IDENTITY 키라 save 시점에 바로 insert 된다
        assertThatThrownBy(() -> {
            modelRepository.save(new AiClusterModel(ClusterModelScope.USER, USER_ID, Map.of(), 11, NOW));
            modelRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 요약에_AI_원본_줄글을_저장한다() {
        BrowsingSession session = sessionRepository.save(new BrowsingSession(USER_ID, "고양이 돌보기", NOW));
        Summary summary = new Summary(session, "");
        Map<String, Object> essay = Map.of("source", "llm", "title", "고양이 돌보기",
                "sections", List.of(Map.of("heading", "음수량", "body", "하루 약 50ml.", "unitKeys", List.of("u1"))));
        summary.replaceEssay(essay, "## 음수량\n\n하루 약 50ml.");
        flushAndClear();

        BrowsingSession found = sessionRepository.findWithSummaryByIdAndDeletedAtIsNull(session.getId())
                .orElseThrow();
        assertThat(found.getSummary().getEssay()).isEqualTo(essay);
        assertThat(found.getSummary().getMarkdown()).startsWith("## 음수량");
    }

    private BrowsingSession failedSession(String collectorSessionId, LocalDateTime failedAt) {
        BrowsingSession session = new BrowsingSession(USER_ID, "실패", NOW, collectorSessionId);
        session.failAnalysis();
        session = sessionRepository.save(session);
        SessionCollection collection = new SessionCollection(session.getId());
        collection.markFailed(failedAt);
        collectionRepository.save(collection);
        return session;
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
