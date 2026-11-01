package com.justinneed.session.collection.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 세션 수집 · AI 호출의 진행 상태 (세션당 1행).
 * sessions 는 목록 조회용으로 가볍게 두고, 스케줄러가 쓰는 시각과 횟수는 여기에 모은다.
 * 값을 바꾸는 쪽은 세션 행을 먼저 잠근다 (BrowsingSessionRepository.lockById).
 */
@Entity
@Table(name = "session_collections")
public class SessionCollection {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    // 시작 요청을 받은 시각. null 이면 청크 · 종료 요청이 먼저 와서 안전장치로 만든 행이다
    @Column(name = "start_requested_at")
    private LocalDateTime startRequestedAt;

    // 마지막 청크를 받은 시각 (자동 종료 기준)
    @Column(name = "last_chunk_at")
    private LocalDateTime lastChunkAt;

    // 종료 요청을 받은 시각. 서버가 재시작해도 이어서 합치려고 DB 에 남긴다
    @Column(name = "end_requested_at")
    private LocalDateTime endRequestedAt;

    // 종료 요청의 endedAt (KST). 자동 종료면 null 이고 합칠 때 마지막 틱 시각을 쓴다
    @Column(name = "requested_ended_at")
    private LocalDateTime requestedEndedAt;

    @Column(name = "stop_reason", length = 50)
    private String stopReason;

    @Column(name = "total_chunks")
    private Integer totalChunks;

    // 원본 형식 문제로 합치기에 실패한 횟수
    @Column(name = "merge_attempts", nullable = false)
    private int mergeAttempts;

    // ANALYZING 으로 바꾸며 AI 호출을 시작한 시각 (GET 을 기다리는 전체 상한 기준)
    @Column(name = "ai_requested_at")
    private LocalDateTime aiRequestedAt;

    // 마지막으로 /analyze 를 보낸 시각 (재전송 간격 기준)
    @Column(name = "ai_last_called_at")
    private LocalDateTime aiLastCalledAt;

    @Column(name = "ai_resend_count", nullable = false)
    private int aiResendCount;

    // 재전송에 연달아 응답이 없었던 횟수. 응답이 오면 0 으로 돌아간다
    @Column(name = "ai_unanswered_count", nullable = false)
    private int aiUnansweredCount;

    // AI 가 GET /input 을 부른 시각 (콜백 대기 기준)
    @Column(name = "input_fetched_at")
    private LocalDateTime inputFetchedAt;

    // FAILED 로 바뀐 시각 (청크 보관 기한 기준)
    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    protected SessionCollection() {
    }

    public SessionCollection(Long sessionId) {
        this.sessionId = sessionId;
    }

    public void markStartRequested(LocalDateTime requestedAt) {
        this.startRequestedAt = requestedAt;
    }

    public void chunkReceived(LocalDateTime receivedAt) {
        this.lastChunkAt = receivedAt;
    }

    // 재전송이면 처음 받은 값을 그대로 둔다
    public void requestEnd(LocalDateTime endedAt, String stopReason, int totalChunks, LocalDateTime requestedAt) {
        if (isEndRequested()) {
            return;
        }
        this.requestedEndedAt = endedAt;
        this.stopReason = stopReason;
        this.totalChunks = totalChunks;
        this.endRequestedAt = requestedAt;
    }

    public boolean isEndRequested() {
        return endRequestedAt != null;
    }

    // 종료 요청이 있으면 청크가 다 왔거나 누락 대기가 끝났을 때, 없으면 자동 종료 시간이 지났을 때
    public boolean isMergeDue(long chunkCount, LocalDateTime sessionStartedAt, Duration missingChunkWait,
            Duration autoEndAfter, LocalDateTime now) {
        if (isEndRequested()) {
            return chunkCount >= totalChunks || !endRequestedAt.plus(missingChunkWait).isAfter(now);
        }
        LocalDateTime lastActivity = lastChunkAt != null ? lastChunkAt : sessionStartedAt;
        return !lastActivity.plus(autoEndAfter).isAfter(now);
    }

    public int increaseMergeAttempts() {
        return ++mergeAttempts;
    }

    public void startAiRequest(LocalDateTime requestedAt) {
        this.aiRequestedAt = requestedAt;
        this.aiLastCalledAt = requestedAt;
        this.aiResendCount = 0;
        this.aiUnansweredCount = 0;
        this.inputFetchedAt = null;
    }

    // 첫 재전송은 firstDelay 뒤, 응답이 없었으면 secondDelay 뒤
    public boolean isResendDue(Duration firstDelay, Duration secondDelay, LocalDateTime now) {
        Duration delay = aiUnansweredCount == 0 ? firstDelay : secondDelay;
        return !aiLastCalledAt.plus(delay).isAfter(now);
    }

    public void markResent(LocalDateTime resentAt) {
        this.aiLastCalledAt = resentAt;
        this.aiResendCount++;
    }

    public void resendAnswered() {
        this.aiUnansweredCount = 0;
    }

    public int resendUnanswered() {
        return ++aiUnansweredCount;
    }

    public void markInputFetched(LocalDateTime fetchedAt) {
        this.inputFetchedAt = fetchedAt;
    }

    public void markFailed(LocalDateTime failedAt) {
        this.failedAt = failedAt;
    }

    // 재분석은 처음부터 다시 잰다
    public void resetForReanalysis() {
        this.mergeAttempts = 0;
        this.aiRequestedAt = null;
        this.aiLastCalledAt = null;
        this.aiResendCount = 0;
        this.aiUnansweredCount = 0;
        this.inputFetchedAt = null;
        this.failedAt = null;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public LocalDateTime getStartRequestedAt() {
        return startRequestedAt;
    }

    public LocalDateTime getLastChunkAt() {
        return lastChunkAt;
    }

    public LocalDateTime getEndRequestedAt() {
        return endRequestedAt;
    }

    public LocalDateTime getRequestedEndedAt() {
        return requestedEndedAt;
    }

    public String getStopReason() {
        return stopReason;
    }

    public Integer getTotalChunks() {
        return totalChunks;
    }

    public int getMergeAttempts() {
        return mergeAttempts;
    }

    public LocalDateTime getAiRequestedAt() {
        return aiRequestedAt;
    }

    public LocalDateTime getAiLastCalledAt() {
        return aiLastCalledAt;
    }

    public int getAiResendCount() {
        return aiResendCount;
    }

    public int getAiUnansweredCount() {
        return aiUnansweredCount;
    }

    public LocalDateTime getInputFetchedAt() {
        return inputFetchedAt;
    }

    public LocalDateTime getFailedAt() {
        return failedAt;
    }
}
