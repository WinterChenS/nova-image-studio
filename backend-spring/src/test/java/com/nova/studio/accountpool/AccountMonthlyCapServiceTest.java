package com.nova.studio.accountpool;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.audit.UsageRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T26 (WIN-29) — 账号月度费用上限：monthly_cap_cost 达限 → 自动 paused（不删除），
 * admin 解除（resume）后恢复调度。检查按自然月累计（当月 cost 快照求和），
 * 由周期任务触发；未配置上限 / 非 active 账号不动。
 */
class AccountMonthlyCapServiceTest {

    private AccountRepository accountRepository;
    private UsageRecordRepository usageRepository;
    private AuditLogService auditLogService;
    private AccountMonthlyCapService service;

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        usageRepository = mock(UsageRecordRepository.class);
        auditLogService = mock(AuditLogService.class);
        service = new AccountMonthlyCapService(accountRepository, usageRepository, auditLogService);
    }

    private AccountRepository.Row row(String status, BigDecimal cap) {
        return new AccountRepository.Row(ACCOUNT_ID, "Gemini-主账号", "google-gemini", "https://api.example.com",
                "v1:iv:enc", "[]", status, 100, cap, "{}", null, null,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void pausesAccountWhenMonthCostReachesCap() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", new BigDecimal("100"))));
        when(usageRepository.sumCostByAccount(eq(ACCOUNT_ID), any(), any()))
                .thenReturn(new BigDecimal("100.50"));

        boolean paused = service.checkAndPause(ACCOUNT_ID);

        assertThat(paused).isTrue();
        verify(accountRepository).updateStatus(ACCOUNT_ID, "paused");
        verify(auditLogService).log(eq(null), eq("account.cap-paused"), eq("ai_accounts"),
                eq(ACCOUNT_ID.toString()), any());
    }

    @Test
    void doesNotPauseWhenUnderCap() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", new BigDecimal("100"))));
        when(usageRepository.sumCostByAccount(eq(ACCOUNT_ID), any(), any()))
                .thenReturn(new BigDecimal("99.99"));

        boolean paused = service.checkAndPause(ACCOUNT_ID);

        assertThat(paused).isFalse();
        verify(accountRepository, never()).updateStatus(eq(ACCOUNT_ID), any());
    }

    @Test
    void ignoresAccountWithoutCap() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", null)));

        boolean paused = service.checkAndPause(ACCOUNT_ID);

        assertThat(paused).isFalse();
        verify(usageRepository, never()).sumCostByAccount(any(), any(), any());
        verify(accountRepository, never()).updateStatus(eq(ACCOUNT_ID), any());
    }

    @Test
    void doesNotTouchNonActiveAccounts() {
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("paused", new BigDecimal("10"))));

        boolean paused = service.checkAndPause(ACCOUNT_ID);

        assertThat(paused).isFalse();
        verify(usageRepository, never()).sumCostByAccount(any(), any(), any());
        verify(accountRepository, never()).updateStatus(eq(ACCOUNT_ID), any());
    }

    @Test
    void sweepChecksAllCappedActiveAccounts() {
        UUID other = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(accountRepository.findById(ACCOUNT_ID)).thenReturn(Optional.of(row("active", new BigDecimal("10"))));
        when(accountRepository.listAll()).thenReturn(java.util.List.of(
                row("active", new BigDecimal("10")),
                new AccountRepository.Row(other, "B", "openai", "https://x", "v1:iv:enc", "[]",
                        "active", 100, null, "{}", null, null, Instant.now(), Instant.now())));
        when(usageRepository.sumCostByAccount(any(), any(), any())).thenReturn(new BigDecimal("999"));

        service.checkAllAccounts();

        verify(accountRepository).updateStatus(ACCOUNT_ID, "paused");
        verify(accountRepository, never()).updateStatus(other, "paused");
    }

    @Test
    void monthCostUsesCurrentCalendarMonth() {
        YearMonth month = YearMonth.now();
        service.monthCost(ACCOUNT_ID, month);
        verify(usageRepository).sumCostByAccount(eq(ACCOUNT_ID),
                eq(month.atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()),
                eq(month.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant()));
    }
}
