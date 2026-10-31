package com.justinneed.community.bookmark;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "community_bookmarks",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "selection_key"}),
        indexes = {@Index(columnList = "user_id,save_type,saved_at"),
                @Index(name = "idx_community_bookmarks_card_session", columnList = "card_session_id")})
public class CommunityBookmark {
    public static final int NAME_MAX_LENGTH = 30;

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
    // 카드 북마크의 세션. 노드를 골라 저장한 북마크는 null 이고 카드 숫자에 들어가지 않는다
    @Column(name = "card_session_id")
    private Long cardSessionId;
    // 해시태그 묶음의 이름. 묶음이 아니거나 이름을 비워 저장하면 null 이다
    @Column(length = NAME_MAX_LENGTH)
    private String name;
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
    /** 카드 북마크. 노드 대상의 미니 마인드맵이라 읽기 규칙이 노드를 골라 저장한 것과 같다. */
    public static CommunityBookmark ofCard(Long userId, String selectionKey, Long sessionId, Long ownerId,
            List<Long> nodeIds, BookmarkSnapshot snapshot, Instant savedAt) {
        var bookmark = new CommunityBookmark(userId, SaveType.MINI_MINDMAP, TargetType.NODE, selectionKey,
                ownerId, nodeIds, snapshot, savedAt);
        bookmark.cardSessionId = sessionId;
        return bookmark;
    }
    public void rename(String name) { this.name = name; }
    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public SaveType getSaveType() { return saveType; }
    public TargetType getTargetType() { return targetType; }
    public String getName() { return name; }
    public Long getSourceMindmapId() { return sourceMindmapId; }
    public Long getCardSessionId() { return cardSessionId; }
    public List<Long> getTargetIds() { return List.copyOf(targetIds); }
    public BookmarkSnapshot getSnapshot() { return snapshot; }
    public Instant getSavedAt() { return savedAt; }
}
