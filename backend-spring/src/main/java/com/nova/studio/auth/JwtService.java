package com.nova.studio.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT HS256 issuing/validation (T2.2). The signing secret comes from
 * {@code NOVA_JWT_SECRET} in {@code .env}; tokens carry {@code sub} = user id,
 * {@code username} and {@code role}, and expire after 24h (H7: short-lived,
 * no refresh token).
 */
@Service
public class JwtService {

    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final SecretKey key;

    public JwtService(@Value("${NOVA_JWT_SECRET:}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("NOVA_JWT_SECRET 未配置（.env），无法签发 JWT");
        }
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) {
            throw new IllegalStateException("NOVA_JWT_SECRET 至少需要 32 字节（base64 编码 48 字节为推荐值）");
        }
        this.key = Keys.hmacShaKeyFor(raw);
    }

    public String issue(UUID userId, String username, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(TOKEN_TTL)))
                .signWith(key)
                .compact();
    }

    /** Parse + validate a token; returns null when missing/invalid/expired. */
    public AuthUser parse(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token.trim())
                    .getPayload();
            String subject = claims.getSubject();
            if (subject == null) {
                return null;
            }
            String username = claims.get("username", String.class);
            String role = claims.get("role", String.class);
            if (username == null || role == null) {
                return null;
            }
            return new AuthUser(UUID.fromString(subject), username, role);
        } catch (Exception e) {
            return null;
        }
    }
}
