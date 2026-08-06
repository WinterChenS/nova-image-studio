package com.nova.studio.accountpool;

import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T4 (WIN-28) — account scheduler: least in-flight + round-robin tie-break +
 * cooldown exclusion + broken/paused/deleted skip + protocol/scope cross-check
 * (ADR-34), and the no-candidate error (H1/A5). A3/A4.
 */
class AccountSchedulerTest {

    private AccountService accountService;
    private AccountHealthService healthService;
    private AccountScheduler scheduler;

    private static final UUID ACCOUNT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ADMIN = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        accountService = mock(AccountService.class);
        healthService = mock(AccountHealthService.class);
        // 协议/scope/status 过滤是 AccountService.isCandidate 的职责（AccountServiceTest 覆盖）；
        // 调度器自身职责 = 在飞/轮询/冷却/排除/无候选报错。
        when(accountService.isCandidate(any(), any())).thenReturn(true);
        when(healthService.isCoolingDown(any())).thenReturn(false);
        scheduler = new AccountScheduler(accountService, healthService);
    }

    private AccountRepository.Row row(UUID id, String status, String protocol, String scope) {
        return new AccountRepository.Row(id, "账号-" + id, protocol, "https://api.example.com/v1",
                "v1:iv:sk-" + id, scope, status, 100, null, "{}", null, ADMIN,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    private CatalogModelRepository.Row model(String protocol) {
        return new CatalogModelRepository.Row(MODEL_ID, "image", protocol, "模型", "gpt-image-1",
                "https://api.example.com/v1", "{}", null, true, ADMIN,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    @Test
    void selectPicksLeastInflightAccount() {
        var a = row(ACCOUNT_A, "active", "openai", "[]");
        var b = row(ACCOUNT_B, "active", "openai", "[]");
        when(accountService.activeAccounts()).thenReturn(List.of(a, b));
        when(accountService.decryptKey(a)).thenReturn("key-a");
        when(accountService.decryptKey(b)).thenReturn("key-b");

        // A already busy with 2 in-flight → B must win
        scheduler.acquire(ACCOUNT_A);
        scheduler.acquire(ACCOUNT_A);
        var selected = scheduler.select(model("openai"), Set.of());

        assertThat(selected.accountId()).isEqualTo(ACCOUNT_B);
        assertThat(selected.apiKey()).isEqualTo("key-b");
    }

    @Test
    void selectRoundRobinsOnEqualInflight() {
        var a = row(ACCOUNT_A, "active", "openai", "[]");
        var b = row(ACCOUNT_B, "active", "openai", "[]");
        when(accountService.activeAccounts()).thenReturn(List.of(a, b));
        when(accountService.decryptKey(any())).thenReturn("key");

        UUID first = scheduler.select(model("openai"), Set.of()).accountId();
        UUID second = scheduler.select(model("openai"), Set.of()).accountId();

        assertThat(first).isNotEqualTo(second);   // 轮询平局决胜
    }

    @Test
    void selectSkipsCoolingDownAccount() {
        var a = row(ACCOUNT_A, "active", "openai", "[]");
        var b = row(ACCOUNT_B, "active", "openai", "[]");
        when(accountService.activeAccounts()).thenReturn(List.of(a, b));
        when(accountService.decryptKey(any())).thenReturn("key");
        when(healthService.isCoolingDown(a)).thenReturn(true);   // A 冷却中 → 不参与

        assertThat(scheduler.select(model("openai"), Set.of()).accountId()).isEqualTo(ACCOUNT_B);
    }

    @Test
    void selectExcludesExplicitlyTriedAccounts() {
        var a = row(ACCOUNT_A, "active", "openai", "[]");
        var b = row(ACCOUNT_B, "active", "openai", "[]");
        when(accountService.activeAccounts()).thenReturn(List.of(a, b));
        when(accountService.decryptKey(any())).thenReturn("key");

        // 换账号重试：排除已试账号 A → 必须选 B
        assertThat(scheduler.select(model("openai"), Set.of(ACCOUNT_A)).accountId()).isEqualTo(ACCOUNT_B);
    }

    @Test
    void acquireReleaseTracksInflight() {
        var a = row(ACCOUNT_A, "active", "openai", "[]");
        var b = row(ACCOUNT_B, "active", "openai", "[]");
        when(accountService.activeAccounts()).thenReturn(List.of(a, b));
        when(accountService.decryptKey(any())).thenReturn("key");

        scheduler.acquire(ACCOUNT_A);
        scheduler.acquire(ACCOUNT_A);
        assertThat(scheduler.select(model("openai"), Set.of()).accountId()).isEqualTo(ACCOUNT_B);

        scheduler.release(ACCOUNT_A);
        scheduler.release(ACCOUNT_A);
        // 在飞清零后 A 重新可用（least-in-flight）
        assertThat(scheduler.select(model("openai"), Set.of()).accountId()).isEqualTo(ACCOUNT_A);
    }

    @Test
    void noCandidateThrowsClearError() {
        when(accountService.activeAccounts()).thenReturn(List.of());
        assertThatThrownBy(() -> scheduler.select(model("openai"), Set.of()))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getMessage()).contains("可用账号");
                });
    }
}
