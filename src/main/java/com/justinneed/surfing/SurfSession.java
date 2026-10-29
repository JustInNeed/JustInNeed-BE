package com.justinneed.surfing;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
@Entity @Getter
@Table(name="surf_sessions", indexes=@Index(columnList="userId,state"))
public class SurfSession {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long userId;
    @Enumerated(EnumType.STRING) @Column(nullable=false) private State state=State.COLLECTING;
    private Instant startedAt; private Instant lastActivityAt; private Instant endedAt;
    private String endReason; private String failure; private Long recordId; private int attempts;
    protected SurfSession() {}
    public SurfSession(Long user, Instant now) { userId=user; startedAt=now; lastActivityAt=now; }
    public void touch(Instant now) { lastActivityAt=now; }
    public void end(Instant at, String reason) { endedAt=at; endReason=reason; state=State.ANALYZING; }
    public void retry() { state=State.ANALYZING; failure=null; }
    public void attempt() { attempts++; }
    public void done(Long id) { recordId=id; state=State.COMPLETED; failure=null; }
    public void fail(String reason) { state=State.FAILED; failure=reason; }
    public enum State { COLLECTING, ANALYZING, COMPLETED, FAILED }
}
