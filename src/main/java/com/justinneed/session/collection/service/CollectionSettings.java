package com.justinneed.session.collection.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 세션 수집 · AI 호출 대기 시간 (app.collection.*). 스케줄러 주기는 CollectionRecoveryScheduler 가 직접 읽는다
@Component
public record CollectionSettings(
        // 마지막 청크 뒤 이 시간 동안 새 청크가 없으면 끝으로 보고 받은 만큼 합친다
        @Value("${app.collection.auto-end-after:1h}") Duration autoEndAfter,
        // 분석에 성공한 세션의 청크를 마지막 청크 뒤 이 기간만 남긴다 (확장 재전송 기간)
        @Value("${app.collection.completed-chunk-retention:3d}") Duration completedChunkRetention,
        // /analyze 뒤 GET /input 이 없을 때 재전송까지 기다리는 시간
        @Value("${app.collection.resend-first-delay:1m}") Duration resendFirstDelay,
        // 재전송에 응답이 없었을 때 다음 재전송까지 기다리는 시간. 이번에도 응답이 없으면 FAILED
        @Value("${app.collection.resend-second-delay:2m}") Duration resendSecondDelay,
        // AI 가 계속 대기 중이어도 ANALYZING 전환 후 이 시간이 지나면 FAILED
        @Value("${app.collection.ai-wait-limit:30m}") Duration aiWaitLimit,
        // GET /input 뒤 콜백을 기다리는 시간
        @Value("${app.collection.callback-timeout:30m}") Duration callbackTimeout,
        // 합치지 못할 때(원본 형식 오류 등) 시도하는 횟수. 다 차면 FAILED
        @Value("${app.collection.merge-max-attempts:3}") int mergeMaxAttempts
) {
}
