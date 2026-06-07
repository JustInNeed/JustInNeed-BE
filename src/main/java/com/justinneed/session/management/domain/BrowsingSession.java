package com.justinneed.session.management.domain;

import com.justinneed.global.common.BaseEntity;
import com.justinneed.session.summary.domain.Summary;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sessions")
public class BrowsingSession extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "page_count", nullable = false)
    private int pageCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.COMPLETED;

    @Column(name = "is_favorite", nullable = false)
    private boolean favorite;

    @Column(name = "is_public", nullable = false)
    private boolean publicSession;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> tags = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Source> sources = new ArrayList<>();

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToOne(mappedBy = "session", fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    private Summary summary;

    protected BrowsingSession() {
    }

    public BrowsingSession(Long userId, String title, LocalDateTime startedAt) {
        this.userId = userId;
        this.title = title;
        this.startedAt = startedAt;
    }

    public void complete(LocalDateTime endedAt, int pageCount) {
        this.endedAt = endedAt;
        this.pageCount = pageCount;
        this.status = SessionStatus.COMPLETED;
    }

    public void replaceSources(List<Source> sources) {
        this.sources = new ArrayList<>(sources);
    }

    public void attachSummary(Summary summary) {
        this.summary = summary;
    }

    public void update(String title, String editedMarkdown, Boolean publicSession, Boolean favorite, List<String> tags) {
        if (title != null) {
            this.title = title;
        }
        if (editedMarkdown != null) {
            getOrCreateSummary().updateMarkdown(editedMarkdown);
        }
        if (publicSession != null) {
            this.publicSession = publicSession;
        }
        if (favorite != null) {
            this.favorite = favorite;
        }
        if (tags != null) {
            this.tags = new ArrayList<>(tags);
        }
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean hasAnyTag(List<String> hashtags) {
        List<String> loweredHashtags = hashtags.stream()
                .map(String::toLowerCase)
                .toList();
        return tags.stream()
                .map(String::toLowerCase)
                .anyMatch(loweredHashtags::contains);
    }

    private Summary getOrCreateSummary() {
        if (summary == null) {
            summary = new Summary(this, title, "");
        }
        return summary;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getTitle() {
        return title;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public int getPageCount() {
        return pageCount;
    }

    public SessionStatus getStatus() {
        return status;
    }

    public boolean isFavorite() {
        return favorite;
    }

    public boolean isPublicSession() {
        return publicSession;
    }

    public List<String> getTags() {
        return tags;
    }

    public List<Source> getSources() {
        return sources;
    }

    public Summary getSummary() {
        return summary;
    }
}
