package com.justinneed.session.analysis.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 확장이 보낸 청크를 합친 세션 원본 (수집기 stepC json 형식, 세션당 1행).
 * 분석 화면은 원본이 아니라 AI 결과로 집계하므로, 원본은 분석 완료 또는 실패 후 21일만 보관한다.
 */
@Entity
@Table(
        name = "session_raw_payloads",
        indexes = @Index(name = "idx_session_raw_payloads_expires", columnList = "expires_at")
)
public class SessionRawPayload {

    public static final int RETENTION_DAYS = 21;

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    // 분석 완료 · 실패 시각 + 21일. 분석 전이면 null (삭제 대상 아님)
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    protected SessionRawPayload() {
    }

    public SessionRawPayload(Long sessionId, Long userId, Map<String, Object> payload, LocalDateTime receivedAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    /** 분석이 끝나면 호출. 그 시점부터 21일 뒤 삭제 대상이 된다. */
    public void markAnalyzed(LocalDateTime analyzedAt) {
        this.expiresAt = analyzedAt.plusDays(RETENTION_DAYS);
    }

    /** 분석이 실패하면 호출. 실패 시점부터 21일 뒤 삭제 대상이 된다. */
    public void markFailed(LocalDateTime failedAt) {
        this.expiresAt = failedAt.plusDays(RETENTION_DAYS);
    }

    /** 재분석하면 호출. 다시 끝날 때까지 삭제 대상에서 뺀다. */
    public void clearExpiry() {
        this.expiresAt = null;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
