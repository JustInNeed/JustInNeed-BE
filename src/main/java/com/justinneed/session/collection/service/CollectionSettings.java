package com.justinneed.session.collection.service;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 세션 수집 · AI 호출 대기 시간 (app.collection.*). 스케줄러 주기는 CollectionRecoveryScheduler 가 직접 읽는다
@Component
public record CollectionSettings(
        // 종료 요청 없이 마지막 청크(청크가 없으면 세션 시작) 후 이 시간이 지나면 자동 종료
        @Value("${app.collection.auto-end-after:30m}") Duration autoEndAfter,
        // 종료 요청의 총 청크 수보다 적게 왔을 때 기다리는 시간
        @Value("${app.collection.missing-chunk-wait:3m}") Duration missingChunkWait,
        // /analyze 뒤 GET /input 이 없을 때 재전송까지 기다리는 시간
        @Value("${app.collection.resend-first-delay:1m}") Duration resendFirstDelay,
        // 재전송에 응답이 없었을 때 다음 재전송까지 기다리는 시간. 이번에도 응답이 없으면 FAILED
        @Value("${app.collection.resend-second-delay:2m}") Duration resendSecondDelay,
        // AI 가 계속 대기 중이어도 ANALYZING 전환 후 이 시간이 지나면 FAILED
        @Value("${app.collection.ai-wait-limit:30m}") Duration aiWaitLimit,
        // GET /input 뒤 콜백을 기다리는 시간
        @Value("${app.collection.callback-timeout:30m}") Duration callbackTimeout,
        // 원본 형식이 깨져 합치지 못할 때 시도하는 횟수
        @Value("${app.collection.merge-max-attempts:3}") int mergeMaxAttempts
) {
}
