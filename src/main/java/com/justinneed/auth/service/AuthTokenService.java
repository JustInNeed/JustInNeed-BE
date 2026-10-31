package com.justinneed.auth.service;

import com.justinneed.auth.domain.RefreshToken;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.jwt.TokenDenylist;
import com.justinneed.auth.repository.MemberRepository;
import com.justinneed.auth.jwt.TokenHashes;
import com.justinneed.auth.repository.RefreshTokenRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * access/refresh 토큰 발급·재발급·로그아웃을 담당.
 * refresh 토큰은 DB 화이트리스트(refresh_tokens)로 관리하고, access 토큰은 무상태 + (로그아웃 · 탈퇴 시) DB denylist(revoked_access_tokens).
 */
@Service
public class AuthTokenService {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenDenylist accessTokenDenylist;
    private final MemberRepository memberRepository;

    public AuthTokenService(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            TokenDenylist accessTokenDenylist,
            MemberRepository memberRepository
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenDenylist = accessTokenDenylist;
        this.memberRepository = memberRepository;
    }

    /** 로그인 시: 새 묶음으로 토큰 발급 + refresh 화이트리스트 등록 */
    @Transactional
    public TokenResponse issue(Long memberId) {
        return issue(memberId, null);
    }

    /** 재발급 · 확장 연결 시: 기존 묶음에 토큰 발급. groupId 가 null 이면 새 묶음 */
    @Transactional
    public TokenResponse issue(Long memberId, String groupId) {
        String group = groupId != null ? groupId : UUID.randomUUID().toString();
        String accessToken = jwtTokenProvider.createAccessToken(memberId, group);
        String refreshToken = jwtTokenProvider.createRefreshToken(memberId);
        saveRefreshToken(memberId, refreshToken, group);
        return new TokenResponse(accessToken, refreshToken);
    }

    /** 아직 로그아웃되지 않은 묶음인지 */
    @Transactional(readOnly = true)
    public boolean isActiveGroup(String groupId) {
        return groupId != null && refreshTokenRepository.existsByGroupId(groupId);
    }

    /** 재발급 시: JWT 검증 → 화이트리스트에서 기존 토큰 삭제(회전) → 재발급 */
    @Transactional
    public TokenResponse reissue(String refreshToken) {
        if (refreshToken == null
                || !jwtTokenProvider.validate(refreshToken)
                || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        String tokenHash = hash(refreshToken);
        String groupId = refreshTokenRepository.findGroupIdByHash(tokenHash);
        // 같은 토큰으로 동시에 재발급해도 행을 실제로 지운 요청 하나만 새 토큰을 받는다 (이미 쓴 토큰 · 없는 토큰도 0건)
        int deleted = refreshTokenRepository.deleteByHash(tokenHash);
        if (deleted != 1) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        Long memberId = jwtTokenProvider.getMemberId(refreshToken);
        // 탈퇴 처리와 겹쳐 토큰이 남은 경우에도 새 토큰을 주지 않는다
        if (memberRepository.existsByIdAndWithdrawnAtIsNotNull(memberId)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return issue(memberId, groupId);
    }

    /** 로그아웃 시: 같은 묶음의 refresh 를 화이트리스트에서 삭제 + 보낸 access 즉시 무효화(denylist) */
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && jwtTokenProvider.validate(accessToken)) {
            accessTokenDenylist.deny(accessToken, jwtTokenProvider.getExpiryEpochSecond(accessToken));
        }
        if (refreshToken != null && jwtTokenProvider.validate(refreshToken)) {
            String tokenHash = hash(refreshToken);
            String groupId = refreshTokenRepository.findGroupIdByHash(tokenHash);
            // 이미 지워졌으면 0건으로 끝난다 (멱등)
            if (groupId != null) {
                refreshTokenRepository.deleteByGroupId(groupId);
            } else {
                refreshTokenRepository.deleteByHash(tokenHash);
            }
        }
    }

    /** 계정 탈퇴 시 현재 access 토큰과 회원에게 발급된 모든 refresh 토큰을 폐기한다. */
    @Transactional
    public void revokeAll(Long memberId, String accessToken) {
        if (accessToken != null && jwtTokenProvider.validate(accessToken)) {
            accessTokenDenylist.deny(accessToken, jwtTokenProvider.getExpiryEpochSecond(accessToken));
        }
        refreshTokenRepository.deleteByMemberId(memberId);
    }

    private void saveRefreshToken(Long memberId, String refreshToken, String groupId) {
        LocalDateTime expiresAt = Instant.ofEpochSecond(jwtTokenProvider.getExpiryEpochSecond(refreshToken))
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        refreshTokenRepository.save(new RefreshToken(memberId, hash(refreshToken), expiresAt, groupId));
    }

    // refresh 토큰 원문 대신 SHA-256 해시를 저장/조회 (DB 유출 시 토큰 노출 방지)
    private String hash(String token) {
        return TokenHashes.sha256(token);
    }
}
