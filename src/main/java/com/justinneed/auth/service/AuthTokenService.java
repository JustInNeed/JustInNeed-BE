package com.justinneed.auth.service;

import com.justinneed.auth.domain.RefreshToken;
import com.justinneed.auth.dto.TokenResponse;
import com.justinneed.auth.jwt.JwtTokenProvider;
import com.justinneed.auth.jwt.TokenDenylist;
import com.justinneed.auth.repository.RefreshTokenRepository;
import com.justinneed.global.exception.CustomException;
import com.justinneed.global.exception.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * access/refresh 토큰 발급·재발급·로그아웃을 담당.
 * refresh 토큰은 DB 화이트리스트(refresh_tokens)로 관리하고, access 토큰은 무상태 + (로그아웃 시) in-memory denylist.
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

    /** 재발급 시: JWT 검증 + 화이트리스트 존재 확인 → 기존 토큰 회전(삭제) 후 재발급 */
    @Transactional
    public TokenResponse reissue(String refreshToken) {
        if (refreshToken == null
                || !jwtTokenProvider.validate(refreshToken)
                || !jwtTokenProvider.isRefreshToken(refreshToken)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(refreshToken))
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_REFRESH_TOKEN));

        refreshTokenRepository.delete(stored); // 회전: 사용된 refresh 토큰은 폐기
        return issue(jwtTokenProvider.getMemberId(refreshToken));
    }

    /** 로그아웃 시: refresh 화이트리스트에서 삭제 + access 즉시 무효화(denylist) */
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        if (accessToken != null && jwtTokenProvider.validate(accessToken)) {
            accessTokenDenylist.deny(accessToken, jwtTokenProvider.getExpiryEpochSecond(accessToken));
        }
        if (refreshToken != null && jwtTokenProvider.validate(refreshToken)) {
            refreshTokenRepository.findByTokenHash(hash(refreshToken))
                    .ifPresent(refreshTokenRepository::delete);
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
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
