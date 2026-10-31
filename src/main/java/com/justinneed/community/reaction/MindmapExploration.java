package com.justinneed.community.reaction;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 카드(세션) 나도 탐색중. 행은 MindmapExplorationRepository.insertIfAbsent 로만 만든다
// TargetType 을 늘리지 않고 표를 따로 둔다: target_type 의 CHECK 제약은 ddl-auto: update 로 바뀌지 않는다
@Entity
@Table(name = "community_mindmap_explorations",
        uniqueConstraints = @UniqueConstraint(columnNames = {"actor_id", "session_id"}),
        indexes = @Index(name = "idx_community_mindmap_explorations_session", columnList = "session_id"))
public class MindmapExploration {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    protected MindmapExploration() { }
}
