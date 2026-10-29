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
        for(var state: new SurfSession.State[]{SurfSession.State.COLLECTING,SurfSession.State.ANALYZING})
            for(var s:state==SurfSession.State.COLLECTING
                ? sessions.findByStateAndLastActivityAtBeforeOrderByIdAsc(state,java.time.Instant.now().minusSeconds(1805),PageRequest.of(0,50))
                : sessions.findByStateOrderByIdAsc(state,PageRequest.of(0,50))) {
                if(state==SurfSession.State.COLLECTING) service.expire(s.getUserId(),s.getId());
                else service.process(s.getUserId(),s.getId());
            }
    }
}
