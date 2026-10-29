package com.justinneed.surfing;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
@Entity @Getter
@Table(name="surf_events",uniqueConstraints=@UniqueConstraint(columnNames={"sessionId","clientId"}),indexes=@Index(columnList="sessionId,occurredAt"))
public class SurfEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    private Long sessionId; private Long userId;
    @Column(length=64,nullable=false) private String clientId;
    @Enumerated(EnumType.STRING) private Type type;
    @Column(length=2048) private String url;
    @Column(length=300) private String title;
    @Column(columnDefinition="text") private String content;
    private Instant occurredAt; private long activeMillis;
    @Enumerated(EnumType.STRING) private Behavior behavior=Behavior.UNKNOWN;
    protected SurfEvent() {}
    public SurfEvent(Long session, Long user, SurfRequests.Event e) {
        sessionId=session; userId=user; clientId=e.clientId(); type=e.type(); url=e.url();
        title=e.title(); content=e.content(); occurredAt=e.occurredAt(); activeMillis=e.activeMillis();
    }
    public void classify(Behavior value) { behavior=value; }
    public enum Type { PAGE_VIEW, MOUSE_MOVE, SCROLL, HOVER, CLICK }
    public enum Behavior { DEEP_READING, READING, SCANNING, NAVIGATION, UNKNOWN }
}
