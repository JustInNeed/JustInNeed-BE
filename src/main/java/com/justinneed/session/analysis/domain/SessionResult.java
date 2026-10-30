package com.justinneed.session.analysis.domain;

import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 가 세션 하나를 분석해 돌려준 결과 (세션당 1행).
 * 화면 · 분석이 세션 단위로 통째로 읽으므로 항목마다 JSON 컬럼 하나로 둔다. 형식은 AI 출력(pipeline/output.py)을 그대로 따른다.
 * 줄글은 summaries.essay, 세션 제목 · 해시태그 · 출처는 sessions 에 둔다.
 */
@Entity
@Table(
        name = "session_results",
        indexes = @Index(name = "idx_session_results_user", columnList = "user_id")
)
public class SessionResult extends BaseEntity {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 마인드맵 노드 · 엣지. LLM 없이 분석한 경우 null
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "mindmap")
    private Map<String, Object> mindmap;

    // 컷오프 유닛 (unitKey 기준 원문 발췌 · 글 제목 · 링크 · 본 시각 · 점수 · 병합된 출처)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selected_units", nullable = false)
    private List<Map<String, Object>> selectedUnits = new ArrayList<>();

    // 분석용 전체 유닛 (Idle 포함, 군집 · 체류시간 · 본 시각)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_units", nullable = false)
    private List<Map<String, Object>> analysisUnits = new ArrayList<>();

    // 읽기 상태별 유닛 수 · 체류시간 합
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_summary", nullable = false)
    private Map<String, Object> analysisSummary = Map.of();

    // 방문 글 목록 (홈 타임라인용, 짧은 방문 제외, 카테고리 포함)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pages", nullable = false)
    private List<Map<String, Object>> pages = new ArrayList<>();

    // AI 상태: 이번 세션 유닛 피처 (다음 세션의 개인 정규화 · 개인 군집 재학습에 사용)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "unit_features", nullable = false)
    private List<Map<String, Object>> unitFeatures = new ArrayList<>();

    // 사용한 군집 모델 (global / user / session_fallback / none)
    @Column(name = "model_source", length = 30)
    private String modelSource;

    // 행동 점수 방식 (A / C)
    @Column(name = "score_method", length = 10)
    private String scoreMethod;

    protected SessionResult() {
    }

    public SessionResult(Long sessionId, Long userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    /** AI 결과 항목을 한 번에 채운다. */
    public void replace(
            Map<String, Object> mindmap,
            List<Map<String, Object>> selectedUnits,
            List<Map<String, Object>> analysisUnits,
            Map<String, Object> analysisSummary,
            List<Map<String, Object>> pages,
            List<Map<String, Object>> unitFeatures,
            String modelSource,
            String scoreMethod
    ) {
        this.mindmap = mindmap;
        this.selectedUnits = new ArrayList<>(selectedUnits);
        this.analysisUnits = new ArrayList<>(analysisUnits);
        this.analysisSummary = analysisSummary;
        this.pages = new ArrayList<>(pages);
        this.unitFeatures = new ArrayList<>(unitFeatures);
        this.modelSource = modelSource;
        this.scoreMethod = scoreMethod;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public Map<String, Object> getMindmap() {
        return mindmap;
    }

    public List<Map<String, Object>> getSelectedUnits() {
        return selectedUnits;
    }

    public List<Map<String, Object>> getAnalysisUnits() {
        return analysisUnits;
    }

    public Map<String, Object> getAnalysisSummary() {
        return analysisSummary;
    }

    public List<Map<String, Object>> getPages() {
        return pages;
    }

    public List<Map<String, Object>> getUnitFeatures() {
        return unitFeatures;
    }

    public String getModelSource() {
        return modelSource;
    }

    public String getScoreMethod() {
        return scoreMethod;
    }
}
