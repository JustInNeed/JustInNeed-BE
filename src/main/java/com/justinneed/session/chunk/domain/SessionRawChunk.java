package com.justinneed.session.chunk.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 확장 프로그램(수집기)이 보낸 세션 원본 청크 (조립 전).
 * (회원, 수집기 세션 id, 구간 id, 청크 번호) 가 같으면 같은 청크로 보고 처음 받은 것만 남긴다 — 확장은 응답을 못 받으면 다시 보낸다.
 * 완료 판정 후 조립해 session_raw_payloads 에 저장하는 것 · 조립 후 삭제 시점은 아직 없음 (TODO).
 */
@Entity
@Table(
        name = "session_raw_chunks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_session_raw_chunks_key",
                columnNames = {"user_id", "collector_session_id", "seg_id", "n"}
        ),
        indexes = @Index(name = "idx_session_raw_chunks_session", columnList = "user_id, collector_session_id")
)
public class SessionRawChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 수집기가 만든 세션 id (UUID). 웹 세션(browsing_sessions.id)과는 조립할 때 잇는다
    @Column(name = "collector_session_id", nullable = false, length = 64)
    private String collectorSessionId;

    // 수집기의 구간 id (한 페이지를 연속으로 본 구간, stepC chunkReport.segId)
    @Column(name = "seg_id", nullable = false, length = 64)
    private String segId;

    // 구간 안의 청크 번호 (1부터)
    @Column(name = "n", nullable = false)
    private int n;

    // 세션이 끝날 때 보내는 마지막 청크 표시. 마지막 청크에만 maxN · stopReason 이 온다
    @Column(name = "is_final", nullable = false)
    private boolean finalChunk;

    @Column(name = "max_n")
    private Integer maxN;

    @Column(name = "stop_reason", length = 50)
    private String stopReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    protected SessionRawChunk() {
    }

    public SessionRawChunk(Long userId, String collectorSessionId, String segId, int n,
                           boolean finalChunk, Integer maxN, String stopReason,
                           Map<String, Object> payload, LocalDateTime receivedAt) {
        this.userId = userId;
        this.collectorSessionId = collectorSessionId;
        this.segId = segId;
        this.n = n;
        this.finalChunk = finalChunk;
        this.maxN = maxN;
        this.stopReason = stopReason;
        this.payload = payload;
        this.receivedAt = receivedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getCollectorSessionId() {
        return collectorSessionId;
    }

    public String getSegId() {
        return segId;
    }

    public int getN() {
        return n;
    }

    public boolean isFinalChunk() {
        return finalChunk;
    }

    public Integer getMaxN() {
        return maxN;
    }

    public String getStopReason() {
        return stopReason;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
