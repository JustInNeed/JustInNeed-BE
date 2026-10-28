package com.justinneed.community.domain;

import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "community_profiles")
public class CommunityProfile extends BaseEntity {
    @Id
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NodeVisibility visibility = NodeVisibility.URL_ONLY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> hiddenHashtags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Long> pinnedSessionIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> interests = new ArrayList<>();

    protected CommunityProfile() {
    }

    public CommunityProfile(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
    public NodeVisibility getVisibility() { return visibility; }
    public List<String> getHiddenHashtags() { return List.copyOf(hiddenHashtags); }
    public List<Long> getPinnedSessionIds() { return List.copyOf(pinnedSessionIds); }
    public List<String> getInterests() { return List.copyOf(interests); }

    public void changeVisibility(NodeVisibility visibility) { this.visibility = visibility; }
    public void replaceHiddenHashtags(List<String> values) { hiddenHashtags = new ArrayList<>(values); }
    public void replacePinnedSessionIds(List<Long> values) { pinnedSessionIds = new ArrayList<>(values); }
    public void replaceInterests(List<String> values) { interests = new ArrayList<>(values); }
}
