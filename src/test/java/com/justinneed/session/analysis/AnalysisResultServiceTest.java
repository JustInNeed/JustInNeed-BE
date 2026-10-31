package com.justinneed.session.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.justinneed.auth.domain.Member;
import com.justinneed.auth.domain.SocialProvider;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import com.justinneed.session.analysis.domain.ClusterModelScope;
import com.justinneed.session.analysis.domain.SessionRawPayload;
import com.justinneed.session.analysis.domain.SessionResult;
import com.justinneed.session.analysis.dto.AiAnalysisResult;
import com.justinneed.session.analysis.repository.AiClusterModelRepository;
import com.justinneed.session.analysis.repository.SessionRawPayloadRepository;
import com.justinneed.session.analysis.repository.SessionResultRepository;
import com.justinneed.session.analysis.service.AnalysisResultService;
import com.justinneed.session.management.domain.BrowsingSession;
import com.justinneed.session.management.domain.SessionStatus;
import com.justinneed.session.management.domain.Source;
import com.justinneed.session.management.repository.BrowsingSessionRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AnalysisResultServiceTest {

    private static final Long USER_ID = 1L;

    // 원본(stepC) 중 링크 판단에 쓰는 부분만. cat.example.com 은 page 가 두 개로 나뉘어 있고, dog 는 컷오프 유닛이 없는 링크
    static final String RAW_JSON = """
            {"kind": "rbc-session",
             "session": {"startedAt": "2026-10-30T00:59:00.000Z"},
             "pages": [
               {"meta": {"url": "https://blog.naver.com/cat/1", "pageId": "https://blog.naver.com/cat/1",
                         "enteredAt": "2026-10-30T01:05:00.000Z"}, "timeline": []},
               {"meta": {"url": "https://docs.spring.io/jpa", "pageId": "https://docs.spring.io/jpa",
                         "enteredAt": "2026-10-30T01:00:00.000Z"}, "timeline": []},
               {"meta": {"url": "https://blog.naver.com/cat/1", "pageId": "https://blog.naver.com/cat/1",
                         "enteredAt": "2026-10-30T01:02:00.000Z"}, "timeline": []},
               {"meta": {"url": "https://dog.example.com/a", "pageId": "https://dog.example.com/a",
                         "enteredAt": "2026-10-30T01:10:00.000Z"}, "timeline": []}
             ]}
            """;

    // AI 출력(pipeline/output.py build_output)과 같은 모양
    static final String RESULT_JSON = """
            {"sessionId": "s-1", "userId": "t-1", "modelSource": "global", "scoreMethod": "C",
             "state": {"unitFeatures": [{"unit_key": "https://blog.naver.com/cat/1#u1", "cursor_speed_mean": 1.2}],
                       "userModel": {"k": 4, "centroids": [[0.1], [0.2], [0.3], [0.4]]}},
             "mindmap": {"structureSource": "llm", "rootSource": "llm",
                         "nodes": [
                           {"id": "root:1", "type": "root", "keyword": "공부", "score": 3.0, "childCount": 2},
                           {"id": "topic:0", "type": "topic", "keyword": "고양이", "score": 2.0, "childCount": 1},
                           {"id": "topic:1", "type": "topic", "keyword": "JPA", "score": 1.0, "childCount": 1}],
                         "edges": []},
             "essay": {"source": "llm", "title": "고양이와 JPA",
                       "sections": [{"heading": "음수량", "body": "하루 50ml", "unitKeys": ["https://blog.naver.com/cat/1#u1"]}]},
             "selected": [
               {"unitKey": "https://blog.naver.com/cat/1#u1", "pageTitle": "고양이 물", "pageUrl": "https://blog.naver.com/cat/1",
                "text": "낮은 점수 발췌", "score": 1.5, "firstSeenAt": "2026-10-30T01:06:00+00:00"},
               {"unitKey": "https://blog.naver.com/cat/1#u2", "pageTitle": "고양이 물", "pageUrl": "https://blog.naver.com/cat/1",
                "text": "높은 점수 발췌", "score": 3.5, "firstSeenAt": "2026-10-30T01:07:00+00:00"},
               {"unitKey": "https://docs.spring.io/jpa#u1", "pageTitle": "JPA 문서", "pageUrl": "https://docs.spring.io/jpa",
                "text": "쿼리 메서드", "score": 2.0, "firstSeenAt": "2026-10-30T01:01:00+00:00"},
               {"unitKey": "https://unknown.example.com/x#u1", "pageTitle": "원본에 없는 글", "pageUrl": "https://unknown.example.com/x",
                "text": "원본 없음", "score": 1.0, "firstSeenAt": "2026-10-30T01:20:00+00:00"}],
             "analysis": {"summary": {"Reading": {"units": 3, "dwellMs": 9000}},
                          "units": [{"unitKey": "https://blog.naver.com/cat/1#u1", "clusterLabel": "Reading"}]},
             "pages": [{"pageId": "https://docs.spring.io/jpa", "url": "https://docs.spring.io/jpa", "title": "JPA 문서",
                        "firstVisitedAt": "2026-10-30T01:00:00+00:00", "dwellMs": 200000}],
             "pageVisits": [{"pageId": "https://docs.spring.io/jpa", "url": "https://docs.spring.io/jpa",
                             "dwellMs": 200000, "visitCount": 2},
                            {"pageId": "https://blog.naver.com/cat/1", "url": "https://blog.naver.com/cat/1",
                             "dwellMs": 5000, "visitCount": 1}]}
            """;

    @Autowired
    private AnalysisResultService service;

    @Autowired
    private BrowsingSessionRepository sessionRepository;

    @Autowired
    private SessionRawPayloadRepository rawRepository;

    @Autowired
    private SessionResultRepository resultRepository;

    @Autowired
    private AiClusterModelRepository modelRepository;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    private Long sessionId;

    @BeforeEach
    void setUp() {
        BrowsingSession session = new BrowsingSession(USER_ID, "분석 전 제목", LocalDateTime.of(2026, 10, 30, 9, 59));
        session.markReady();
        session.startAnalysis(LocalDateTime.of(2026, 10, 30, 10, 30));
        sessionRepository.save(session);
        sessionId = session.getId();
        Map<String, Object> raw = objectMapper.readValue(RAW_JSON, new TypeReference<>() {
        });
        rawRepository.save(new SessionRawPayload(sessionId, USER_ID, raw, LocalDateTime.of(2026, 10, 30, 10, 30)));
    }

    @Test
    void AI_결과를_세션_요약_결과_원본에_나눠_저장한다() {
        service.save(sessionId, result(RESULT_JSON));
        entityManager.flush();
        entityManager.clear();

        BrowsingSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getTitle()).isEqualTo("고양이와 JPA");
        assertThat(session.getSummary().getMarkdown()).isEqualTo("## 음수량\n\n하루 50ml");
        assertThat(session.getSummary().getEssay()).containsEntry("title", "고양이와 JPA").containsKey("sections");
        assertThat(session.getTags()).containsExactly("고양이", "JPA");
        // 원본의 방문 링크 수 (AI pages 는 1개뿐이지만 원본 링크는 cat · spring · dog 3개)
        assertThat(session.getPageCount()).isEqualTo(3);

        // 출처는 링크당 1개, 방문 시각(KST) 순. 발췌는 점수가 가장 높은 유닛
        assertThat(session.getSources()).containsExactly(
                new Source("JPA 문서", "https://docs.spring.io/jpa", "쿼리 메서드", LocalDateTime.of(2026, 10, 30, 10, 0)),
                new Source("고양이 물", "https://blog.naver.com/cat/1", "높은 점수 발췌", LocalDateTime.of(2026, 10, 30, 10, 2)),
                new Source("원본에 없는 글", "https://unknown.example.com/x", "원본 없음", LocalDateTime.of(2026, 10, 30, 10, 20)));

        SessionResult saved = resultRepository.findById(sessionId).orElseThrow();
        assertThat(saved.getUnitFeatures()).hasSize(1);
        assertThat(saved.getSelectedUnits()).hasSize(4);
        assertThat(saved.getAnalysisUnits()).hasSize(1);
        assertThat(saved.getAnalysisSummary()).containsKey("Reading");
        assertThat(saved.getPages()).singleElement()
                .satisfies(page -> assertThat(page).containsEntry("category", "OFFICIAL_DOCS"));
        assertThat(saved.getPageVisits()).hasSize(2)
                .first().satisfies(page -> assertThat(page).containsEntry("visitCount", 2).containsEntry("dwellMs", 200000));
        assertThat(saved.getModelSource()).isEqualTo("global");
        assertThat(saved.getScoreMethod()).isEqualTo("C");

        assertThat(modelRepository.findByScopeAndUserId(ClusterModelScope.USER, USER_ID))
                .hasValueSatisfying(model -> assertThat(model.getSessionCount()).isEqualTo(1));

        LocalDateTime expiresAt = rawRepository.findById(sessionId).orElseThrow().getExpiresAt();
        assertThat(expiresAt).isAfter(LocalDateTime.now().plusDays(20));
    }

    @Test
    void 재학습하지_않았으면_개인_군집_모델을_건드리지_않는다() {
        service.save(sessionId, result(RESULT_JSON.replace(
                "\"userModel\": {\"k\": 4, \"centroids\": [[0.1], [0.2], [0.3], [0.4]]}", "\"userModel\": null")));

        assertThat(modelRepository.findByScopeAndUserId(ClusterModelScope.USER, USER_ID)).isEmpty();
    }

    @Test
    void 줄글이_대체_구조면_저장하지_않고_FAILED_로_바꾼다() {
        assertLlmFailed(RESULT_JSON.replace("\"source\": \"llm\"", "\"source\": \"fallback\""));
    }

    @Test
    void 마인드맵이_대체_구조면_저장하지_않고_FAILED_로_바꾼다() {
        assertLlmFailed(RESULT_JSON.replace("\"structureSource\": \"llm\"", "\"structureSource\": \"fallback\""));
    }

    @Test
    void AI_가_방문_글_전체를_주지_않으면_null_로_둔다() {
        service.save(sessionId, result(RESULT_JSON.replaceFirst("(?s),\\s*\"pageVisits\".*$", "}")));
        entityManager.flush();
        entityManager.clear();

        assertThat(resultRepository.findById(sessionId).orElseThrow().getPageVisits()).isNull();
    }

    @Test
    void LLM_을_호출하지_않아_마인드맵_줄글이_없으면_FAILED_로_바꾼다() {
        AiAnalysisResult full = result(RESULT_JSON);
        AiAnalysisResult withoutLlm = new AiAnalysisResult(full.modelSource(), full.scoreMethod(), full.state(),
                null, null, full.selected(), full.analysis(), full.pages(), full.pageVisits());

        assertThatThrownBy(() -> service.save(sessionId, withoutLlm))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_LLM_FAILED);
        assertNothingSaved();
    }

    @Test
    void 분석_도중_탈퇴했으면_결과를_저장하지_않고_FAILED_로_둔다() {
        Member withdrawn = memberRepository.save(new Member(SocialProvider.KAKAO, "withdrawn-result", null, null));
        withdrawn.withdraw(null, LocalDateTime.now());
        BrowsingSession session = new BrowsingSession(withdrawn.getId(), "분석 전 제목", LocalDateTime.of(2026, 10, 30, 9, 59));
        session.markReady();
        session.startAnalysis(LocalDateTime.of(2026, 10, 30, 10, 30));
        Long withdrawnSessionId = sessionRepository.save(session).getId();
        entityManager.flush();

        assertThatThrownBy(() -> service.save(withdrawnSessionId, result(RESULT_JSON)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_MEMBER_WITHDRAWN);
        assertThat(sessionRepository.findById(withdrawnSessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(resultRepository.findById(withdrawnSessionId)).isEmpty();
        assertThat(modelRepository.findByScopeAndUserId(ClusterModelScope.USER, withdrawn.getId())).isEmpty();
    }

    @Test
    void 분석_중인_세션이_아니면_저장하지_않고_상태도_그대로_둔다() {
        BrowsingSession completed = sessionRepository.findById(sessionId).orElseThrow();
        completed.completeAnalysis("이미 끝남", List.of(), List.of(), 1);
        entityManager.flush();

        assertThatThrownBy(() -> service.save(sessionId, result(RESULT_JSON)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_NOT_IN_PROGRESS);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(resultRepository.findById(sessionId)).isEmpty();
    }

    @Test
    void AI_가_분석_오류를_알리면_FAILED_로_바꾼다() {
        service.fail(sessionId, "RuntimeError: boom");

        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.FAILED);
    }

    @Test
    void 원본이_없으면_FAILED_로_바꾼다() {
        rawRepository.deleteById(sessionId);

        assertThatThrownBy(() -> service.save(sessionId, result(RESULT_JSON)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RAW_PAYLOAD_NOT_FOUND);
        assertThat(sessionRepository.findById(sessionId).orElseThrow().getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(resultRepository.findById(sessionId)).isEmpty();
    }

    private void assertLlmFailed(String json) {
        AiAnalysisResult result = result(json);

        assertThatThrownBy(() -> service.save(sessionId, result))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ANALYSIS_LLM_FAILED);
        assertNothingSaved();
    }

    private void assertNothingSaved() {
        entityManager.flush();
        entityManager.clear();
        BrowsingSession session = sessionRepository.findById(sessionId).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.FAILED);
        assertThat(session.getTitle()).isEqualTo("분석 전 제목");
        assertThat(session.getSummary()).isNull();
        assertThat(resultRepository.findById(sessionId)).isEmpty();
        assertThat(rawRepository.findById(sessionId).orElseThrow().getExpiresAt()).isNull();
    }

    private AiAnalysisResult result(String json) {
        return objectMapper.readValue(json, AiAnalysisResult.class);
    }
}
