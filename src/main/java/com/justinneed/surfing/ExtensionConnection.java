package com.justinneed.surfing;
import jakarta.persistence.*;
import lombok.Getter;
import java.time.Instant;
@Entity @Getter
public class ExtensionConnection {
    @Id private Long userId; private String tokenHash; private Instant expiresAt;
    protected ExtensionConnection() {}
    public ExtensionConnection(Long user, String hash, Instant expiry) { userId=user; tokenHash=hash; expiresAt=expiry; }
}
