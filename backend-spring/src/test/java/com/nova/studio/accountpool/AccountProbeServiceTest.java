package com.nova.studio.accountpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T27 (WIN-29) — 主动健康探测：轻量探活（/models 或最小请求）配合被动失败计数。
 * 仅探测 active 且「从未成功或上次成功超过探测间隔」且不在冷却中的账号；
 * 成功 → recordSuccess（清失败计数），失败 → recordFailure（可能触发 broken）。
 */
class AccountProbeServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-08-07T04:00:00Z");
    private static final Duration INTERVAL = Duration.ofMinutes(10);
    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private AccountService accountService;
    private AccountHealthService healthService;
    private AccountProbeService service;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        healthService = mock(AccountHealthService.class);
        service = new AccountProbeService(accountService, healthService, MAPPER,
                600_000L, Clock.fixed(NOW, ZoneId.of("UTC")));
    }

    private AccountRepository.Row row(String healthJson) {
        return new AccountRepository.Row(ACCOUNT_ID, "主账号", "openai", "https://api.example.com/v1",
                "v1:iv:sk-secret", "[]", "active", 100, new BigDecimal("100"), healthJson, null, null,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void probesAccountsThatNeverSucceeded() {
        when(accountService.activeAccounts()).thenReturn(java.util.List.of(row("{}")));
        when(healthService.isCoolingDown(any())).thenReturn(false);
        when(accountService.probeUpstream(any())).thenReturn(new AccountService.ProbeResult(true, "ok"));

        service.probeDueAccounts();

        verify(accountService).probeUpstream(any());
        verify(healthService).recordSuccess(any());
    }

    @Test
    void probesIdleAccountsOlderThanInterval() {
        String health = "{\"last_success_at\":\"2026-08-07T02:00:00Z\"}"; // 2h ago > 10min
        when(accountService.activeAccounts()).thenReturn(java.util.List.of(row(health)));
        when(healthService.isCoolingDown(any())).thenReturn(false);
        when(accountService.probeUpstream(any())).thenReturn(new AccountService.ProbeResult(false, "上游返回 500"));
        when(healthService.classify(any())).thenReturn(AccountHealthService.ErrorKind.SERVER_ERROR);

        service.probeDueAccounts();

        verify(accountService).probeUpstream(any());
        verify(healthService).recordFailure(any(), eq(AccountHealthService.ErrorKind.SERVER_ERROR), any());
    }

    @Test
    void skipsRecentlySuccessfulAccounts() {
        String health = "{\"last_success_at\":\"2026-08-07T03:58:00Z\"}"; // 2min ago < 10min
        when(accountService.activeAccounts()).thenReturn(java.util.List.of(row(health)));
        when(healthService.isCoolingDown(any())).thenReturn(false);

        service.probeDueAccounts();

        verify(accountService, never()).probeUpstream(any());
    }

    @Test
    void skipsCoolingDownAccounts() {
        String health = "{\"cooldown_until\":\"2026-08-07T05:00:00Z\"}";
        when(accountService.activeAccounts()).thenReturn(java.util.List.of(row(health)));
        when(healthService.isCoolingDown(any())).thenReturn(true);

        service.probeDueAccounts();

        verify(accountService, never()).probeUpstream(any());
    }

    @Test
    void probeFailureRecordsFailureWithKind() {
        when(accountService.activeAccounts()).thenReturn(java.util.List.of(row("{}")));
        when(healthService.isCoolingDown(any())).thenReturn(false);
        when(accountService.probeUpstream(any())).thenReturn(new AccountService.ProbeResult(false, "上游返回 401"));
        when(healthService.classify(any())).thenReturn(AccountHealthService.ErrorKind.UNAUTHORIZED);

        service.probeDueAccounts();

        verify(healthService).recordFailure(any(), eq(AccountHealthService.ErrorKind.UNAUTHORIZED), any());
    }

    @Test
    void needsProbeTrueForEmptyHealthAndFalseForRecentSuccess() {
        assertThat(service.needsProbe(row("{}"))).isTrue();
        String recent = "{\"last_success_at\":\"2026-08-07T03:59:30Z\"}";
        assertThat(service.needsProbe(row(recent))).isFalse();
    }
}
