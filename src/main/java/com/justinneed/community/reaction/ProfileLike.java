package com.justinneed.community.reaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
@Entity
@Table(name = "community_profile_likes", uniqueConstraints = @UniqueConstraint(columnNames = {"actor_id", "profile_id"}))
public class ProfileLike {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_id", nullable = false)
    private Long actorId;
    @Column(name = "profile_id", nullable = false)
    private Long profileId;
    protected ProfileLike() { }
    public ProfileLike(Long actorId, Long profileId) { this.actorId = actorId; this.profileId = profileId; }
}
