package com.justinneed.community.reaction;

import com.justinneed.community.bookmark.TargetType;
import jakarta.persistence.*;

@Entity
@Table(name = "community_explorations", uniqueConstraints =
        @UniqueConstraint(columnNames = {"actor_id", "target_type", "target_id"}))
public class Exploration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Enumerated(EnumType.STRING) @Column(name = "target_type", nullable = false)
    private TargetType targetType;
    @Column(name = "target_id", nullable = false)
    private Long targetId;
    protected Exploration() { }
    public Exploration(Long actorId, TargetType targetType, Long targetId) {
        this.actorId = actorId; this.targetType = targetType; this.targetId = targetId;
    }
}
