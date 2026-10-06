package com.justinneed.auth.domain;

import com.justinneed.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "members",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_member_provider",
                columnNames = {"social_provider", "provider_id"}
        )
)
public class Member extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "social_provider", nullable = false, length = 20)
    private SocialProvider socialProvider;

    // 소셜 프로바이더가 발급하는 고유 사용자 식별자. 재로그인 시 회원 매칭에 필수.
    @Column(name = "provider_id", nullable = false)
    private String providerId;

    // 방법 B: 첫 소셜 로그인 시 null로 생성되고, 이후 닉네임 입력(PATCH)으로 채워짐
    @Column(length = 8, unique = true)
    private String nickname;

    // 선택값: 카카오/네이버 동의 항목에 따라 없을 수 있음
    @Column
    private String email;

    @Column(name = "withdrawal_reason", length = 500)
    private String withdrawalReason;

    @Column(name = "withdrawn_at")
    private LocalDateTime withdrawnAt;

    protected Member() {
    }

    public Member(SocialProvider socialProvider, String providerId, String nickname, String email) {
        this.socialProvider = socialProvider;
        this.providerId = providerId;
        this.nickname = nickname;
        this.email = email;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void withdraw(String reason, LocalDateTime withdrawnAt) {
        this.withdrawalReason = reason;
        this.withdrawnAt = withdrawnAt;
    }

    public boolean isWithdrawn() {
        return withdrawnAt != null;
    }

    // 가입일자 = 엔티티 생성 시각(BaseEntity.createdAt)
    public LocalDateTime getJoinedAt() {
        return getCreatedAt();
    }

    public Long getId() {
        return id;
    }

    public SocialProvider getSocialProvider() {
        return socialProvider;
    }

    public String getProviderId() {
        return providerId;
    }

    public String getNickname() {
        return nickname;
    }

    public String getEmail() {
        return email;
    }
}
