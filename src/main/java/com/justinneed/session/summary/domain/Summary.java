package com.justinneed.session.summary.domain;

import com.justinneed.session.management.domain.BrowsingSession;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "summaries")
public class Summary {

    @Id
    private Long sessionId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private BrowsingSession session;

    @Column(nullable = false, columnDefinition = "text")
    private String markdown;

    // AI 가 만든 줄글 원본 (대제목 + 섹션). markdown 은 이걸 변환한 뒤 사용자가 편집하는 본문
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "essay")
    private Map<String, Object> essay;

    protected Summary() {
    }

    public Summary(BrowsingSession session, String markdown) {
        this.session = session;
        this.markdown = markdown;
        session.attachSummary(this);
    }

    public void updateMarkdown(String markdown) {
        this.markdown = markdown;
    }

    /** AI 가 다시 분석하면 원본 줄글과 본문을 함께 교체한다. */
    public void replaceEssay(Map<String, Object> essay, String markdown) {
        this.essay = essay;
        this.markdown = markdown;
    }

    public Map<String, Object> getEssay() {
        return essay;
    }

    public String getMarkdown() {
        return markdown;
    }
}
