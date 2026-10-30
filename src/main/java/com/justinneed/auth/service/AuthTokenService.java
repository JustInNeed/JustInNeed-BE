package com.justinneed.auth.service;

import com.justinneed.auth.domain.RefreshToken;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.jwt.TokenDenylist;
import com.justinneed.auth.jwt.TokenHashes;
import com.justinneed.auth.repository.RefreshTokenRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

    public AuthTokenService(
            JwtTokenProvider jwtTokenProvider,
            RefreshTokenRepository refreshTokenRepository,
            TokenDenylist accessTokenDenylist
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.refreshTokenRepository = refreshTokenRepository;
        this.accessTokenDenylist = accessTokenDenylist;
    }

    /** 로그인 시: 토큰 발급 + refresh 화이트리스트 등록 */
    @Transactional
    public TokenResponse issue(Long memberId) {
        String accessToken = jwtTokenProvider.createAccessToken(memberId);
        String refreshToken = jwtTokenProvider.createRefreshToken(memberId);
        saveRefreshToken(memberId, refreshToken);
        return new TokenResponse(accessToken, refreshToken);
    }

    /** 재발급 시: JWT 검증 → 화이트리스트에서 기존 토큰 삭제(회전) → 재발급 */
    @Transactional
    public TokenResponse reissue(String refreshToken) {
        if (refreshToken == null
                || !jwtTokenProvider.validate(refreshToken)
                || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        // 같은 토큰으로 동시에 재발급해도 행을 실제로 지운 요청 하나만 새 토큰을 받는다 (이미 쓴 토큰 · 없는 토큰도 0건)
        int deleted = refreshTokenRepository.deleteByHash(hash(refreshToken));
        if (deleted != 1) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        return issue(jwtTokenProvider.getMemberId(refreshToken));
    }

    /** 로그아웃 시: refresh 화이트리스트에서 삭제 + access 즉시 무효화(denylist) */
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && jwtTokenProvider.validate(accessToken)) {
            accessTokenDenylist.deny(accessToken, jwtTokenProvider.getExpiryEpochSecond(accessToken));
        }
        if (refreshToken != null && jwtTokenProvider.validate(refreshToken)) {
            refreshTokenRepository.deleteByHash(hash(refreshToken)); // 이미 지워졌으면 0건으로 끝난다 (멱등)
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

    private void saveRefreshToken(Long memberId, String refreshToken) {
        LocalDateTime expiresAt = Instant.ofEpochSecond(jwtTokenProvider.getExpiryEpochSecond(refreshToken))
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime();
        refreshTokenRepository.save(new RefreshToken(memberId, hash(refreshToken), expiresAt));
    }

    // refresh 토큰 원문 대신 SHA-256 해시를 저장/조회 (DB 유출 시 토큰 노출 방지)
    private String hash(String token) {
        return TokenHashes.sha256(token);
    }
}
