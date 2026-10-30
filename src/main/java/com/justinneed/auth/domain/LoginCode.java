package com.justinneed.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

/**
 * 소셜 로그인 성공 후 FE 에 토큰 대신 넘기는 일회용 로그인 코드. (LoginCodeService 가 발급 · 교환)
 * 코드 원문은 저장하지 않고 SHA-256 해시만 보관한다.
 * - 로그인 성공: 행 생성 (60초 유효)
 * - POST /auth/token 교환: 행 삭제 (한 번만 사용)
 * - 교환되지 않고 만료된 행은 LoginCodeCleanupScheduler 가 정리
 */
@Entity
@Table(name = "login_codes")
public class LoginCode {

    @Id
    @Column(name = "code_hash", length = 64)
    private String codeHash;

    @Column(name = "member_id", nullable = false)
    private Long memberId;

    // 로그인 시점에 닉네임이 없었는지. 교환 응답의 needsNickname 으로 그대로 내려간다
    @Column(name = "needs_nickname", nullable = false)
    private boolean needsNickname;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    protected LoginCode() {
    }

    public LoginCode(String codeHash, Long memberId, boolean needsNickname, LocalDateTime expiresAt) {
        this.codeHash = codeHash;
        this.memberId = memberId;
        this.needsNickname = needsNickname;
        this.expiresAt = expiresAt;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public Long getMemberId() {
        return memberId;
    }

    public boolean isNeedsNickname() {
        return needsNickname;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
