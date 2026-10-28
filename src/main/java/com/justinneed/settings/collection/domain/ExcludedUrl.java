package com.justinneed.settings.collection.domain;

import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "excluded_urls",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_excluded_url_member_url",
                columnNames = {"member_id", "url"}
        )
)
public class ExcludedUrl extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(nullable = false, length = 253)
    private String domain;

    @Column(name = "is_default", nullable = false)
    private boolean defaultUrl;

    @Column(name = "is_excluded", nullable = false)
    private boolean excluded;

    protected ExcludedUrl() {
    }

    public ExcludedUrl(Long memberId, String url, String domain, boolean defaultUrl) {
        this.memberId = memberId;
        this.url = url;
        this.domain = domain;
        this.defaultUrl = defaultUrl;
        this.excluded = true;
    }

    public void updateExcluded(boolean excluded) {
        this.excluded = excluded;
    }

    public Long getId() {
        return id;
    }

    public Long getMemberId() {
        return memberId;
    }

    public String getUrl() {
        return url;
    }

    public String getDomain() {
        return domain;
    }

    public boolean isDefaultUrl() {
        return defaultUrl;
    }

    public boolean isExcluded() {
        return excluded;
    }
}
