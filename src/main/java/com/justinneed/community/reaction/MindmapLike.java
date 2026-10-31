package com.justinneed.community.reaction;

import jakarta.persistence.*;
import java.time.LocalDateTime;

// 카드(세션) 좋아요. 행은 MindmapLikeRepository.insertIfAbsent 로만 만든다
// 카드가 공개에서 빠지거나 누른 사람이 탈퇴해도 지우지 않고 읽을 때 뺀다
@Entity
@Table(name = "community_mindmap_likes",
        uniqueConstraints = @UniqueConstraint(columnNames = {"actor_id", "session_id"}),
        indexes = @Index(name = "idx_community_mindmap_likes_session", columnList = "session_id"))
public class MindmapLike {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    protected MindmapLike() { }
}
