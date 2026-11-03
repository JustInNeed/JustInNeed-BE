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
 * 확장 프로그램(수집기)이 보낸 세션 원본 청크 (합치기 전).
 * (회원, 수집기 세션 id, 청크 번호) 가 같으면 같은 청크로 보고 처음 받은 것만 남긴다 — 확장은 응답을 못 받으면 다시 보낸다.
 * 다 오면(또는 마지막 청크 뒤 1시간) 합쳐서 session_raw_payloads 에 저장한다 (ChunkSessionService).
 * 합친 뒤에도 다시 온 청크의 내용 비교(409)에 쓰므로 남겨 둔다. 분석 성공 세션은 마지막 청크를 받은 뒤 3일, 실패 세션은 실패 후 21일 뒤 삭제한다.
 * 옛 칸(seg_id · n)은 ddl-auto 가 지우거나 바꾸지 않는다 — 운영 DB 는 db/migrate-session-collection.sql 로 옮긴다.
 */
@Entity
@Table(
        name = "session_raw_chunks",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_session_raw_chunks_chunk",
                columnNames = {"user_id", "collector_session_id", "chunk_id"}
        ),
        indexes = @Index(name = "idx_session_raw_chunks_session", columnList = "user_id, collector_session_id")
)
public class SessionRawChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // 수집기가 만든 세션 id (UUID). sessions.collector_session_id 로 세션과 이어진다
    @Column(name = "collector_session_id", nullable = false, length = 64)
    private String collectorSessionId;

    // 세션 안의 청크 번호 (요청의 n, 1부터 빈틈없이). DB 칸 이름은 chunk_id 다
    @Column(name = "chunk_id", nullable = false)
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

    public SessionRawChunk(Long userId, String collectorSessionId, int n,
                           boolean finalChunk, Integer maxN, String stopReason,
                           Map<String, Object> payload, LocalDateTime receivedAt) {
        this.userId = userId;
        this.collectorSessionId = collectorSessionId;
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
