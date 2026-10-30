package com.justinneed.auth.jwt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 토큰 · 일회용 코드는 원문 대신 SHA-256 해시(hex 64자)로 저장/조회한다. (DB 유출 시 원문 노출 방지)
 */
public final class TokenHashes {

    private TokenHashes() {
    }

    public static String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 미지원 환경", e);
        }
    }
}
