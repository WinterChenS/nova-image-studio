package com.nova.studio.accountpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T4 (WIN-28) — account health state machine (ADR-25): failure counting with
 * per-kind cooldowns (429 60s < 5xx 300s < 401/4xx 600s), consecutive-failure
 * threshold → broken (persisted, never auto-recovers, A4/A22), success resets.
 */
class AccountHealthServiceTest {

    private AccountService accountService;
    private AccountHealthService health;

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        health = new AccountHealthService(accountService, MAPPER, 3);
    }

    private AccountRepository.Row row(String healthJson) {
        return new AccountRepository.Row(ACCOUNT_ID, "账号", "openai", "https://api.example.com/v1",
                "v1:iv:sk-x", "[]", "active", 100, null, healthJson, null, ADMIN,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    // ===== cooldown durations =====

    @Test
    void rateLimitedFailureSetsShortCooldown() {
        health.recordFailure(row("{}"), AccountHealthService.ErrorKind.RATE_LIMITED, "429 too many");

        assertThat(health.isCoolingDown(row("{}"))).isTrue();
        verify(accountService).updateHealth(any(), any());
    }

    @Test
    void successClearsCooldown() {
        health.recordFailure(row("{}"), AccountHealthService.ErrorKind.RATE_LIMITED, "429");
        health.recordSuccess(row("{}"));

        assertThat(health.isCoolingDown(row("{}"))).isFalse();
    }

    // ===== broken state machine (A4/A22) =====

    @Test
    void threeConsecutiveFailuresMarksBroken() {
        for (int i = 0; i < 3; i++) {
            health.recordFailure(row("{}"), AccountHealthService.ErrorKind.UNAUTHORIZED, "401");
        }
        verify(accountService).markBroken(ACCOUNT_ID);
        // broken 后不再参与调度（isCandidate 按 status 排除）
    }

    @Test
    void belowThresholdDoesNotMarkBroken() {
        for (int i = 0; i < 2; i++) {
            health.recordFailure(row("{}"), AccountHealthService.ErrorKind.UNAUTHORIZED, "401");
        }
        verify(accountService, never()).markBroken(ACCOUNT_ID);
    }

    @Test
    void successBeforeThresholdResetsCount() {
        health.recordFailure(row("{}"), AccountHealthService.ErrorKind.UNAUTHORIZED, "401");
        health.recordFailure(row("{}"), AccountHealthService.ErrorKind.UNAUTHORIZED, "401");
        health.recordSuccess(row("{}"));
        health.recordFailure(row("{}"), AccountHealthService.ErrorKind.UNAUTHORIZED, "401");

        verify(accountService, never()).markBroken(ACCOUNT_ID);
    }

    @Test
    void restoredCooldownFromPersistedHealth() {
        // 重启后：health JSONB 中 cooldown_until 未过期 → 仍视为冷却中
        String persisted = "{\"consecutive_failures\":1,\"cooldown_until\":\""
                + Instant.now().plusSeconds(300) + "\"}";
        assertThat(health.isCoolingDown(row(persisted))).isTrue();
    }

    @Test
    void expiredCooldownFromPersistedHealthIsIgnored() {
        String persisted = "{\"consecutive_failures\":1,\"cooldown_until\":\""
                + Instant.now().minusSeconds(10) + "\"}";
        assertThat(health.isCoolingDown(row(persisted))).isFalse();
    }

    // ===== error classification (E.3) =====

    @Test
    void classifies401AsUnauthorized() {
        assertThat(health.classify(new RuntimeException("401 Unauthorized")))
                .isEqualTo(AccountHealthService.ErrorKind.UNAUTHORIZED);
    }

    @Test
    void classifies429AsRateLimited() {
        assertThat(health.classify(new RuntimeException("HTTP 429 Too Many Requests")))
                .isEqualTo(AccountHealthService.ErrorKind.RATE_LIMITED);
    }

    @Test
    void classifies5xxAsServerError() {
        assertThat(health.classify(new RuntimeException("500 Internal Server Error")))
                .isEqualTo(AccountHealthService.ErrorKind.SERVER_ERROR);
    }

    @Test
    void classifiesNetworkTimeoutAsServerError() {
        assertThat(health.classify(new RuntimeException("connect timed out")))
                .isEqualTo(AccountHealthService.ErrorKind.SERVER_ERROR);
    }

    @Test
    void classifiesOther4xxAsRejected() {
        assertThat(health.classify(new RuntimeException("400 bad request")))
                .isEqualTo(AccountHealthService.ErrorKind.REJECTED);
    }

    // ===== retriability (R3 boundary) =====

    @Test
    void retriableKinds() {
        assertThat(AccountHealthService.ErrorKind.RATE_LIMITED.retriable()).isTrue();
        assertThat(AccountHealthService.ErrorKind.SERVER_ERROR.retriable()).isTrue();
        assertThat(AccountHealthService.ErrorKind.UNAUTHORIZED.retriable()).isFalse();
        assertThat(AccountHealthService.ErrorKind.REJECTED.retriable()).isFalse();
    }
}
