package com.justinneed.taggroup.domain;

import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tag_groups")
public class TagGroup extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 50)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> hashtags = new ArrayList<>();

    @Column(nullable = false)
    private int position;

    protected TagGroup() {
    }

    public TagGroup(Long userId, String name, List<String> hashtags, int position) {
        this.userId = userId;
        this.name = name;
        this.hashtags = new ArrayList<>(hashtags);
        this.position = position;
    }

    public void update(String name, List<String> hashtags) {
        if (name != null) {
            this.name = name;
        }
        if (hashtags != null) {
            this.hashtags = new ArrayList<>(hashtags);
        }
    }

    public void updatePosition(int position) {
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public List<String> getHashtags() {
        return hashtags;
    }

    public int getPosition() {
        return position;
    }
}
