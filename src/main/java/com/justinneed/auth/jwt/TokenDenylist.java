package com.justinneed.auth.jwt;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 로그아웃 처리된 토큰을 무효화하기 위한 denylist.
 * 러프 단계: in-memory 저장(앱 재시작 시 비워짐). 운영 단계에선 Redis/DB 로 교체 권장.
 * 각 토큰은 자체 만료 시각까지만 보관하고, 만료된 항목은 조회 시 정리한다.
 */
@Component
public class TokenDenylist {

    // token -> 만료 epoch second
    private final Map<String, Long> denied = new ConcurrentHashMap<>();

    public void deny(String token, long expiryEpochSecond) {
        denied.put(token, expiryEpochSecond);
    }

    public boolean isDenied(String token) {
        Long expiry = denied.get(token);
        if (expiry == null) {
            return false;
        }
        if (expiry < Instant.now().getEpochSecond()) {
            denied.remove(token); // 이미 만료된 토큰은 보관 불필요
            return false;
        }
        return true;
    }
}
