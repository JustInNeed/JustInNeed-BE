package com.justinneed.community.catalog;

import jakarta.persistence.*;
import java.util.Locale;

@Entity
@Table(name = "community_hashtags", uniqueConstraints =
        @UniqueConstraint(columnNames = {"owner_id", "normalized_name"}))
public class CommunityHashtag {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "owner_id", nullable = false)
    private Long ownerId;
    @Column(nullable = false, length = 10)
    private String name;
    @Column(name = "normalized_name", nullable = false, length = 10)
    private String normalizedName;

    protected CommunityHashtag() { }
    public CommunityHashtag(Long ownerId, String name) {
        this.ownerId = ownerId;
        this.name = name;
        this.normalizedName = name.toLowerCase(Locale.ROOT);
    }
    public Long getId() { return id; }
    public Long getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
}
