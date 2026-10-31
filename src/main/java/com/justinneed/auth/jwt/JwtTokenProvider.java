package com.justinneed.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

    private static final String CLAIM_TYPE = "type";
    private static final String CLAIM_GROUP = "grp";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey key;
    private final long accessValiditySeconds;
    private final long refreshValiditySeconds;

    public JwtTokenProvider(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.access-token-validity-seconds}") long accessValiditySeconds,
            @Value("${app.jwt.refresh-token-validity-seconds}") long refreshValiditySeconds
    ) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessValiditySeconds = accessValiditySeconds;
        this.refreshValiditySeconds = refreshValiditySeconds;
    }

    public String createAccessToken(Long memberId) {
        return createAccessToken(memberId, null);
    }

    // groupId: 함께 로그아웃되는 토큰 묶음 (RefreshToken.groupId). 확장 연결 코드를 발급할 때 현재 묶음을 찾는 데 쓴다
    public String createAccessToken(Long memberId, String groupId) {
        return create(memberId, TYPE_ACCESS, accessValiditySeconds, groupId);
    }

    public String createRefreshToken(Long memberId) {
        return create(memberId, TYPE_REFRESH, refreshValiditySeconds, null);
    }

    private String create(Long memberId, String type, long validitySeconds, String groupId) {
        Instant now = Instant.now();
        return Jwts.builder()
                // iat 가 초 단위라 jti 가 없으면 같은 초에 만든 토큰이 글자까지 똑같아진다
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(memberId))
                .claim(CLAIM_TYPE, type)
                .claim(CLAIM_GROUP, groupId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(validitySeconds)))
                .signWith(key)
                .compact();
    }

    public boolean validate(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public boolean isAccessToken(String token) {
        return TYPE_ACCESS.equals(parse(token).get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(String token) {
        return TYPE_REFRESH.equals(parse(token).get(CLAIM_TYPE, String.class));
    }

    public Long getMemberId(String token) {
        return Long.valueOf(parse(token).getSubject());
    }

    /** 묶음이 없으면 null (묶음 도입 전에 발급된 토큰) */
    public String getGroupId(String token) {
        return parse(token).get(CLAIM_GROUP, String.class);
    }

    public long getExpiryEpochSecond(String token) {
        return parse(token).getExpiration().toInstant().getEpochSecond();
    }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
