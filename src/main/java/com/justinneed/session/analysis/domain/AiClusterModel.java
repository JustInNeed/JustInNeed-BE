package com.justinneed.session.analysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * AI 읽기 상태 군집 모델 (스케일러 + centroid + 군집 순서, AI 의 ClusterModel.to_dict 형식).
 * 전체 사용자 모델(GLOBAL, user_id 없음) 1개 + 개인 모델(USER) 사용자당 1개. 재학습하면 행을 교체한다.
 */
@Entity
@Table(
        name = "ai_cluster_models",
        uniqueConstraints = @UniqueConstraint(name = "uk_ai_cluster_models_scope_user", columnNames = {"scope", "user_id"})
)
public class AiClusterModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ClusterModelScope scope;

    @Column(name = "user_id")
    private Long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> model;

    // 학습에 쓴 세션 수 (재학습 주기 판단용)
    @Column(name = "session_count", nullable = false)
    private int sessionCount;

    @Column(name = "fitted_at", nullable = false)
    private LocalDateTime fittedAt;

    protected AiClusterModel() {
    }

    public AiClusterModel(ClusterModelScope scope, Long userId, Map<String, Object> model, int sessionCount,
            LocalDateTime fittedAt) {
        this.scope = scope;
        this.userId = scope == ClusterModelScope.GLOBAL ? null : userId;
        this.model = model;
        this.sessionCount = sessionCount;
        this.fittedAt = fittedAt;
    }

    public void replace(Map<String, Object> model, int sessionCount, LocalDateTime fittedAt) {
        this.model = model;
        this.sessionCount = sessionCount;
        this.fittedAt = fittedAt;
    }

    public Long getId() {
        return id;
    }

    public ClusterModelScope getScope() {
        return scope;
    }

    public Long getUserId() {
        return userId;
    }

    public Map<String, Object> getModel() {
        return model;
    }

    public int getSessionCount() {
        return sessionCount;
    }

    public LocalDateTime getFittedAt() {
        return fittedAt;
    }
}
