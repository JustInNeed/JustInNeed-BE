package com.justinneed.community.follow;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

// 행은 CommunityFollowRepository.insertIfAbsent 로만 만든다. 탈퇴해도 지우지 않고 읽을 때 뺀다
@Entity
@Table(name = "community_follows",
        uniqueConstraints = @UniqueConstraint(columnNames = {"actor_id", "target_id"}),
        indexes = @Index(name = "idx_community_follows_target", columnList = "target_id"))
public class CommunityFollow {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "target_id", nullable = false)
    private Long targetId;
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    protected CommunityFollow() { }
}
