package com.nova.studio.audit;

import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T10 (WIN-28) — audit query service: filtered paginated detail + summary
 * cards (A9) and CSV export with complete columns and name resolution.
 */
class AuditQueryServiceTest {

    private UsageRecordRepository repository;
    private UserRepository userRepository;
    private CatalogModelRepository catalogRepository;
    private AccountRepository accountRepository;
    private AuditQueryService service;

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        repository = mock(UsageRecordRepository.class);
        userRepository = mock(UserRepository.class);
        catalogRepository = mock(CatalogModelRepository.class);
        accountRepository = mock(AccountRepository.class);
        service = new AuditQueryService(repository, userRepository, catalogRepository, accountRepository);
    }

    private UsageRecordRepository.Row row() {
        return new UsageRecordRepository.Row(1L, USER, ACCOUNT, MODEL, "openai", "image",
                "task", "task-1", "success", 100L, 200L, 2, new BigDecimal("0.8"), "CNY", 1500L,
                Instant.parse("2026-08-06T10:00:00Z"));
    }

    @Test
    void queryReturnsItemsTotalAndSummary() {
        when(repository.search(any(), anyInt(), anyInt())).thenReturn(List.of(row()));
        when(repository.count(any())).thenReturn(42L);
        when(repository.summary(any())).thenReturn(Map.of(
                "requestCount", 42L, "successCount", 40L, "successRate", 95.2,
                "totalCost", new BigDecimal("33.6"), "totalTokens", 12600L));
        when(userRepository.findById(USER)).thenReturn(Optional.of(
                new UserRepository.UserRow(USER, "alice", "hash", "user", "active", null, null, null)));
        when(catalogRepository.findById(MODEL)).thenReturn(Optional.of(
                new CatalogModelRepository.Row(MODEL, "image", "openai", "模型A", "gpt-image-1",
                        "http://x", "{}", null, true, null, null, null)));
        when(accountRepository.findById(ACCOUNT)).thenReturn(Optional.of(
                new AccountRepository.Row(ACCOUNT, "主账号", "openai", "http://x", "enc", "[]",
                        "active", 100, null, "{}", null, null, null, null)));

        Map<String, Object> result = service.query(new UsageRecordRepository.Filters(null, null, null, null, null, null, null, null), 1, 50);

        assertThat(result.get("total")).isEqualTo(42L);
        assertThat(((List<?>) result.get("items"))).hasSize(1);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("items")).get(0);
        assertThat(item.get("username")).isEqualTo("alice");
        assertThat(item.get("modelName")).isEqualTo("模型A");
        assertThat(item.get("accountName")).isEqualTo("主账号");
        assertThat(result.get("summary")).isNotNull();
    }

    @Test
    void exportProducesCsvWithHeaderAndRows() {
        when(repository.export(any())).thenReturn(List.of(row()));
        when(userRepository.findById(USER)).thenReturn(Optional.of(
                new UserRepository.UserRow(USER, "alice", "hash", "user", "active", null, null, null)));
        when(catalogRepository.findById(MODEL)).thenReturn(Optional.of(
                new CatalogModelRepository.Row(MODEL, "image", "openai", "模型A", "gpt-image-1",
                        "http://x", "{}", null, true, null, null, null)));
        when(accountRepository.findById(ACCOUNT)).thenReturn(Optional.of(
                new AccountRepository.Row(ACCOUNT, "主账号", "openai", "http://x", "enc", "[]",
                        "active", 100, null, "{}", null, null, null, null)));

        String csv = service.exportCsv(new UsageRecordRepository.Filters(null, null, null, null, null, null, null, null));

        String[] lines = csv.split("\r?\n");
        assertThat(lines[0]).contains("时间", "用户", "模型", "账号", "协议", "类型", "费用", "状态");
        assertThat(lines[1]).contains("alice", "模型A", "主账号", "openai", "0.8", "success");
    }

    @Test
    void exportCsvEscapesCommasAndQuotes() {
        when(userRepository.findById(USER)).thenReturn(Optional.of(
                new UserRepository.UserRow(USER, "al,ice\"x", "hash", "user", "active", null, null, null)));
        when(catalogRepository.findById(MODEL)).thenReturn(Optional.empty());
        when(accountRepository.findById(ACCOUNT)).thenReturn(Optional.empty());
        when(repository.export(any())).thenReturn(List.of(row()));

        String csv = service.exportCsv(new UsageRecordRepository.Filters(null, null, null, null, null, null, null, null));

        assertThat(csv).contains("\"al,ice\"\"x\"");   // RFC-4180 转义
    }
}
