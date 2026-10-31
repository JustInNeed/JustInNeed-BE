package com.justinneed.community.card;

import com.justinneed.community.card.CommunityCard.RelatedUrl;
import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 공개 카드 조건을 만족하는 세션의 미리 계산해 둔 행. CommunityCardSync 만 쓴다.
 * 작성자의 공개 범위 · 비공개 해시태그는 저장하지 않고 읽을 때 적용한다 (바꿔도 행을 다시 쓰지 않도록).
 */
@Entity
@Table(name = "community_cards", indexes = @Index(name = "idx_community_cards_owner", columnList = "owner_id"))
public class CommunityCardRow extends BaseEntity {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Column(nullable = false, length = 100)
    private String title;

    // 요약 본문 첫 문단의 일반 텍스트. 본문이 없으면 null
    @Column(length = CardSnippet.MAX_LENGTH)
    private String snippet;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> hashtags = new ArrayList<>();

    // 앞에서부터 최대 10개. 고유 URL 수는 url_count
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<RelatedUrl> urls = new ArrayList<>();

    @Column(name = "ai_node_count", nullable = false)
    private int aiNodeCount;

    @Column(name = "url_count", nullable = false)
    private int urlCount;

    // 세션 기록이 끝난 시각, 없으면 시작 시각
    @Column(name = "last_activity_at", nullable = false)
    private LocalDateTime lastActivityAt;

    protected CommunityCardRow() {
    }

    public CommunityCardRow(Long sessionId, Long ownerId) {
        this.sessionId = sessionId;
        this.ownerId = ownerId;
    }

    public void replace(String title, String snippet, List<String> hashtags, List<RelatedUrl> urls,
            int aiNodeCount, int urlCount, LocalDateTime lastActivityAt) {
        this.title = title;
        this.snippet = snippet;
        this.hashtags = new ArrayList<>(hashtags);
        this.urls = new ArrayList<>(urls);
        this.aiNodeCount = aiNodeCount;
        this.urlCount = urlCount;
        this.lastActivityAt = lastActivityAt;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getSnippet() {
        return snippet;
    }

    public List<String> getHashtags() {
        return hashtags;
    }

    public List<RelatedUrl> getUrls() {
        return urls;
    }

    public int getAiNodeCount() {
        return aiNodeCount;
    }

    public int getUrlCount() {
        return urlCount;
    }

    public LocalDateTime getLastActivityAt() {
        return lastActivityAt;
    }
}
