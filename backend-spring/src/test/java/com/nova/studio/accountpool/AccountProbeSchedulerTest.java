package com.nova.studio.accountpool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T27 (WIN-29) — active health probe (H4): the scheduled probe iterates
 * {@code active} accounts and runs the lightweight upstream call; results feed
 * the passive health state machine (recordSuccess/recordFailure). Only active
 * accounts are probed; broken/paused/deleted are skipped.
 */
class AccountProbeSchedulerTest {

    private AccountService accountService;
    private AccountHealthService healthService;
    private AccountProbeScheduler probe;

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        healthService = mock(AccountHealthService.class);
        probe = new AccountProbeScheduler(accountService, healthService);
    }

    private AccountRepository.Row row(String status) {
        return new AccountRepository.Row(UUID.randomUUID(), "账号", "openai", "https://api.example.com/v1",
                "v1:iv:sk-xxx", "[]", status, 100, null, "{}", null,
                UUID.randomUUID(), Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    @Test
    void probesOnlyActiveAccounts() {
        when(accountService.listRows()).thenReturn(List.of(row("active"), row("paused"), row("broken"), row("deleted")));
        when(accountService.probe(any())).thenReturn(MapOfOk(true));
        probe.probeAll();
        // active 账号被探测；其余状态跳过
        verify(accountService).probe(org.mockito.ArgumentMatchers.argThat(r -> "active".equals(r.status())));
    }

    @Test
    void probeSuccessRecordsHealthSuccess() {
        AccountRepository.Row active = row("active");
        when(accountService.listRows()).thenReturn(List.of(active));
        when(accountService.probe(active)).thenReturn(MapOfOk(true));
        probe.probeAll();
        verify(healthService).recordSuccess(active);
    }

    @Test
    void probeFailureRecordsHealthFailure() {
        AccountRepository.Row active = row("active");
        when(accountService.listRows()).thenReturn(List.of(active));
        when(accountService.probe(active)).thenReturn(MapOfOk(false));
        probe.probeAll();
        verify(healthService).recordFailure(any(), any(), any());
    }

    @Test
    void noAccountsNoOp() {
        when(accountService.listRows()).thenReturn(List.of());
        probe.probeAll();
        verify(healthService, never()).recordSuccess(any());
    }

    private static java.util.Map<String, Object> MapOfOk(boolean ok) {
        return java.util.Map.of("ok", ok, "message", ok ? "连接成功" : "上游错误");
    }
}
