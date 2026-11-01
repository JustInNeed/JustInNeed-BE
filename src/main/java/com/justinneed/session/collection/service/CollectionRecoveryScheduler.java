package com.justinneed.session.collection.service;

import java.time.LocalDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 앞선 실행이 끝난 뒤 주기만큼 쉬고 다시 돈다. app.collection.scheduler-enabled=false 면 등록하지 않는다
@Component
@ConditionalOnProperty(name = "app.collection.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class CollectionRecoveryScheduler {

    private final CollectionRecoveryService recoveryService;

    public CollectionRecoveryScheduler(CollectionRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${app.collection.scheduler-interval:30s}")
    public void run() {
        recoveryService.run(LocalDateTime.now());
    }
}
