package com.justinneed.community.activity;

import com.justinneed.community.bookmark.TargetType;
import jakarta.persistence.*;
import java.time.*;

@Entity
@Table(name = "community_activities", uniqueConstraints = @UniqueConstraint(columnNames =
        {"actor_id", "target_type", "target_id", "tag_id", "kind", "activity_day"}),
        indexes = @Index(columnList = "occurred_at"))
public class CommunityActivity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false)
    private TargetType targetType;
    @Column(name = "target_id", nullable = false)
    private Long targetId;
    @Column(name = "tag_id", nullable = false)
    private Long tagId;
    @Enumerated(EnumType.STRING) @Column(nullable = false)
    private ActivityKind kind;
    @Column(name = "activity_day", nullable = false)
    private LocalDate activityDay;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    protected CommunityActivity() { }
    public CommunityActivity(Long actorId, TargetType type, Long targetId, Long tagId,
            ActivityKind kind, Instant occurredAt, LocalDate day) {
        this.actorId = actorId; this.targetType = type; this.targetId = targetId; this.tagId = tagId;
        this.kind = kind; this.occurredAt = occurredAt; this.activityDay = day;
    }
    public Long getActorId() { return actorId; }
    public TargetType getTargetType() { return targetType; }
    public Long getTargetId() { return targetId; }
    public Long getTagId() { return tagId; }
    public ActivityKind getKind() { return kind; }
    public LocalDate getActivityDay() { return activityDay; }
    public Instant getOccurredAt() { return occurredAt; }
}
