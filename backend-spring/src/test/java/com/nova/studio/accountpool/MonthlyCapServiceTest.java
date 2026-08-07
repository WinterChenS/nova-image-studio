package com.nova.studio.accountpool;

import com.nova.studio.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
 * T26 (WIN-29) — monthly cost cap (F-31): when an account's current-month
 * usage cost reaches {@code monthly_cap_cost}, the account is automatically
 * {@code paused} (never deleted) and the transition is written to audit_log;
 * an admin later resumes it (existing resume path). No cap configured → no-op.
 */
class MonthlyCapServiceTest {

    private AccountRepository repository;
    private AccountService accountService;
    private AuditLogService auditLog;
    private MonthlyCapService service;

    private static final UUID ACCOUNT_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        repository = mock(AccountRepository.class);
        accountService = mock(AccountService.class);
        auditLog = mock(AuditLogService.class);
        service = new MonthlyCapService(repository, accountService, auditLog);
    }

    private AccountRepository.Row row(String status, BigDecimal cap) {
        return new AccountRepository.Row(ACCOUNT_ID, "主账号", "openai", "https://api.example.com/v1",
                "v1:iv:sk-xxx", "[]", status, 100, cap, "{}", null,
                UUID.randomUUID(), Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    @Test
    void pausesWhenMonthlyCostReachesCap() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(java.util.Optional.of(row("active", new BigDecimal("10.0000"))));
        when(repository.monthlyCost(ACCOUNT_ID)).thenReturn(new BigDecimal("12.5000"));
        service.enforceAfterUsage(ACCOUNT_ID);
        verify(accountService).pause(ACCOUNT_ID);
        verify(auditLog).record(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void noPauseWhenBelowCap() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(java.util.Optional.of(row("active", new BigDecimal("10.0000"))));
        when(repository.monthlyCost(ACCOUNT_ID)).thenReturn(new BigDecimal("9.9999"));
        service.enforceAfterUsage(ACCOUNT_ID);
        verify(accountService, never()).pause(any());
    }

    @Test
    void noPauseWhenNoCapConfigured() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(java.util.Optional.of(row("active", null)));
        when(repository.monthlyCost(ACCOUNT_ID)).thenReturn(new BigDecimal("9999.0000"));
        service.enforceAfterUsage(ACCOUNT_ID);
        verify(accountService, never()).pause(any());
    }

    @Test
    void noPauseWhenAlreadyPausedOrBroken() {
        when(repository.findById(ACCOUNT_ID)).thenReturn(java.util.Optional.of(row("paused", new BigDecimal("1.0000"))));
        when(repository.monthlyCost(ACCOUNT_ID)).thenReturn(new BigDecimal("5.0000"));
        service.enforceAfterUsage(ACCOUNT_ID);
        verify(accountService, never()).pause(any());
    }
}
