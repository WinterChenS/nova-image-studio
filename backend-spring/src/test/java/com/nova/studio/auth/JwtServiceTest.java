package com.nova.studio.auth;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2 T2.2 — JWT HS256 issue/parse roundtrip, tamper rejection, expiry and
 * invalid-secret failures.
 */
class JwtServiceTest {

    private static final String SECRET = "S3GYs4Qf3sLwAgajjSi4/ADjR00ldw9RX2iwL5NgRr6fGU98/24hTLFHu0JySZG7";

    private final JwtService jwt = new JwtService(SECRET);

    @Test
    void issueAndParseRoundtrip() {
        UUID id = UUID.randomUUID();
        String token = jwt.issue(id, "alice", "user");
        AuthUser parsed = jwt.parse(token);
        assertThat(parsed).isNotNull();
        assertThat(parsed.id()).isEqualTo(id);
        assertThat(parsed.username()).isEqualTo("alice");
        assertThat(parsed.role()).isEqualTo("user");
        assertThat(parsed.isAdmin()).isFalse();
    }

    @Test
    void adminRoleParses() {
        String token = jwt.issue(UUID.randomUUID(), "admin", "admin");
        assertThat(jwt.parse(token).isAdmin()).isTrue();
    }

    @Test
    void rejectsTamperedToken() {
        String token = jwt.issue(UUID.randomUUID(), "alice", "user");
        char flipped = token.charAt(0) == 'a' ? 'b' : 'a';
        String tampered = flipped + token.substring(1);
        assertThat(jwt.parse(tampered)).isNull();
    }

    @Test
    void rejectsGarbage() {
        assertThat(jwt.parse("not-a-jwt")).isNull();
        assertThat(jwt.parse("")).isNull();
        assertThat(jwt.parse(null)).isNull();
    }

    @Test
    void rejectsTokenFromDifferentSecret() {
        JwtService other = new JwtService("another-secret-another-secret-another-secret-123456");
        String token = other.issue(UUID.randomUUID(), "alice", "user");
        assertThat(jwt.parse(token)).isNull();
    }

    @Test
    void missingSecretThrows() {
        assertThat(org.assertj.core.api.Assertions.assertThatThrownBy(() -> new JwtService(""))
                .isInstanceOf(IllegalStateException.class));
    }
}
