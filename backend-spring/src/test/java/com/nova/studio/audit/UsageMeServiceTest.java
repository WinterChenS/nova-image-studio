package com.nova.studio.audit;

import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.CatalogModelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T24 (WIN-29) — 我的用量 service: user self-service query that is <b>always
 * scoped to the caller</b> (A11: 用户间用量隔离) — the API surface never accepts
 * a target userId, so no cross-user read is possible. Items + summary come from
 * usage_records; the daily trend comes from usage_daily_agg (T25, survives
 * detail cleanup).
 */
class UsageMeServiceTest {

    private UsageRecordRepository repository;
    private UsageAggRepository aggRepository;
    private UsageMeService service;

    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        repository = mock(UsageRecordRepository.class);
        aggRepository = mock(UsageAggRepository.class);
        service = new UsageMeService(repository, aggRepository,
                mock(CatalogModelRepository.class), mock(AccountRepository.class));
    }

    private UsageRecordRepository.Row row() {
        return new UsageRecordRepository.Row(1L, ME, null, MODEL, "openai", "text", "proxy",
                "req-1", "success", 10L, 5L, null, new BigDecimal("0.02"), "CNY", 120L,
                Instant.parse("2026-08-06T12:00:00Z"));
    }

    @Test
    void myUsageScopesAllQueriesToCaller() {
        when(repository.searchByUser(eq(ME), any(), any(), eq(0), eq(20))).thenReturn(List.of(row()));
        when(repository.countByUser(eq(ME), any(), any())).thenReturn(1L);
        when(repository.summaryByUser(eq(ME), any(), any())).thenReturn(Map.of(
                "requestCount", 1L, "successCount", 1L, "totalCost", new BigDecimal("0.02"), "totalTokens", 15L));
        when(aggRepository.rowsForUser(eq(ME), any(), any())).thenReturn(List.of());

        Map<String, Object> result = service.myUsage(ME, null, null, 1, 20);

        assertThat(result).containsEntry("total", 1L);
        assertThat(result).containsEntry("page", 1);
        assertThat(result).containsEntry("size", 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("modelId", MODEL.toString()).containsEntry("status", "success");
        // WIN-45 回归加固：items 明细项必须携带 refId（与 admin 用量导出 AuditQueryService/CSV 契约一致），
        // 否则 /api/nova/usage/me 前端无法对账（QA-WIN45 BUG-1）
        assertThat(items.get(0)).containsEntry("refId", "req-1");
        // A11: 查询只经 user 维度，从不走管理员全量查询面
        verify(repository).searchByUser(eq(ME), any(), any(), eq(0), eq(20));
        verify(repository, never()).search(any(), anyInt(), anyInt());
        verify(repository, never()).export(any());
    }

    @Test
    void myUsageReturnsDailyTrendFromAggregation() {
        when(repository.searchByUser(eq(ME), any(), any(), eq(0), eq(20))).thenReturn(List.of());
        when(repository.countByUser(eq(ME), any(), any())).thenReturn(0L);
        when(repository.summaryByUser(eq(ME), any(), any())).thenReturn(Map.of(
                "requestCount", 0L, "successCount", 0L, "totalCost", BigDecimal.ZERO, "totalTokens", 0L));
        when(aggRepository.rowsForUser(eq(ME), any(), any())).thenReturn(List.of(
                new UsageAggRepository.DailyAggRow(LocalDate.of(2026, 8, 6), ME, MODEL, null, "text",
                        3, 3, 30L, 15L, new BigDecimal("0.06"), "CNY")));

        Map<String, Object> result = service.myUsage(ME, null, null, 1, 20);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) result.get("daily");
        assertThat(daily).hasSize(1);
        assertThat(daily.get(0)).containsEntry("requestCount", 3).containsEntry("cost", new BigDecimal("0.06"));
    }
}
