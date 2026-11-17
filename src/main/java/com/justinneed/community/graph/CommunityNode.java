package com.justinneed.community.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
@Entity
@Table(name = "community_nodes", uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "source_key"}))
public class CommunityNode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long ownerId;
    @Column(name = "session_id", nullable = false)
    private Long sessionId;
    @Column(name = "source_key", nullable = false, length = 64)
    private String sourceKey;

    protected CommunityNode() { }
    public CommunityNode(Long ownerId, Long sessionId, String sourceKey) {
        this.ownerId = ownerId; this.sessionId = sessionId; this.sourceKey = sourceKey;
    }
    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public Long getSessionId() { return sessionId; }
    public String getSourceKey() { return sourceKey; }
}
