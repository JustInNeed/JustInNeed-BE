package com.justinneed.surfing;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import org.springframework.data.domain.PageRequest;
@Configuration @EnableScheduling
public class SurfWorker {
    private final SurfSessionRepository sessions; private final SurfService service;
    public SurfWorker(SurfSessionRepository sessions,SurfService service) { this.sessions=sessions; this.service=service; }
    @Scheduled(fixedDelayString="${app.surfing.poll-ms:10000}")
    public void run() {
        for(var s:sessions.findByStateAndLastActivityAtBeforeOrderByIdAsc(SurfSession.State.COLLECTING,
                java.time.Instant.now().minusSeconds(1805),PageRequest.of(0,50)))
            service.expire(s.getUserId(),s.getId());
    }
    @Scheduled(fixedDelayString="${app.surfing.poll-ms:10000}")
    public void analyze() {
        for(var s:sessions.findByStateOrderByIdAsc(SurfSession.State.ANALYZING,PageRequest.of(0,50)))
            service.process(s.getUserId(),s.getId());
    }
    @org.springframework.context.annotation.Bean
    public org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler taskScheduler() {
        var scheduler=new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("surf-worker-");
        return scheduler;
    }
}
