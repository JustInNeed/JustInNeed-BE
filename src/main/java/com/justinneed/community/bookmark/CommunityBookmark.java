package com.justinneed.community.bookmark;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "community_bookmarks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "selection_key"}),
        indexes = @Index(columnList = "user_id,save_type,saved_at"))
public class CommunityBookmark {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "user_id", nullable = false)
    private Long userId;
    @Enumerated(EnumType.STRING) @Column(name = "save_type", nullable = false, length = 30)
    private SaveType saveType;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private TargetType targetType;
    @Column(name = "selection_key", nullable = false, length = 64)
    private String selectionKey;
    private Long sourceMindmapId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false)
    private List<Long> targetIds;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false)
    private BookmarkSnapshot snapshot;
    @Column(name = "saved_at", nullable = false)
    private Instant savedAt;

    protected CommunityBookmark() { }
    public CommunityBookmark(Long userId, SaveType saveType, TargetType targetType, String selectionKey,
            Long sourceMindmapId, List<Long> targetIds, BookmarkSnapshot snapshot, Instant savedAt) {
        this.userId = userId; this.saveType = saveType; this.targetType = targetType;
        this.selectionKey = selectionKey; this.sourceMindmapId = sourceMindmapId;
        this.targetIds = List.copyOf(targetIds); this.snapshot = snapshot; this.savedAt = savedAt;
    }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public SaveType getSaveType() { return saveType; }
    public TargetType getTargetType() { return targetType; }
    public Long getSourceMindmapId() { return sourceMindmapId; }
    public List<Long> getTargetIds() { return List.copyOf(targetIds); }
    public BookmarkSnapshot getSnapshot() { return snapshot; }
    public Instant getSavedAt() { return savedAt; }
}
