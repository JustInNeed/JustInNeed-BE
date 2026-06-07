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
import java.util.ArrayList;
import java.util.List;
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

    @Column(nullable = false, length = 120)
    private String heading;

    @Column(nullable = false, columnDefinition = "text")
    private String markdown;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> insights = new ArrayList<>();

    protected Summary() {
    }

    public Summary(BrowsingSession session, String heading, String markdown) {
        this.session = session;
        this.heading = heading;
        this.markdown = markdown;
        session.attachSummary(this);
    }

    public void updateMarkdown(String markdown) {
        this.markdown = markdown;
    }

    public String getHeading() {
        return heading;
    }

    public String getMarkdown() {
        return markdown;
    }

    public List<String> getInsights() {
        return insights;
    }
}
