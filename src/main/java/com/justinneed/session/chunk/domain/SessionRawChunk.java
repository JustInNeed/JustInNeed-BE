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
 * (회원, 수집기 세션 id, 청크 id) 가 같으면 같은 청크로 보고 처음 받은 것만 남긴다 — 확장은 응답을 못 받으면 다시 보낸다.
 * 세션이 끝나면 합쳐서 session_raw_payloads 에 저장한다 (SessionMergeService).
 * 분석에 성공하면 삭제하고, 실패하면 실패 후 21일 뒤 삭제한다.
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

    // 수집기가 만든 청크 id. 중복 판단에만 쓰고 순서로 쓰지 않는다
    @Column(name = "chunk_id", nullable = false, length = 128)
    private String chunkId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    private Map<String, Object> payload;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    protected SessionRawChunk() {
    }

    public SessionRawChunk(Long userId, String collectorSessionId, String chunkId,
                           Map<String, Object> payload, LocalDateTime receivedAt) {
        this.userId = userId;
        this.collectorSessionId = collectorSessionId;
        this.chunkId = chunkId;
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

    public String getChunkId() {
        return chunkId;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public LocalDateTime getReceivedAt() {
        return receivedAt;
    }
}
