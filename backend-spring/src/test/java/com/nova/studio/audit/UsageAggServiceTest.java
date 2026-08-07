package com.nova.studio.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T25 (WIN-29) — usage daily aggregation (A10): the scheduled task aggregates
 * yesterday AND today into {@code usage_daily_agg} (snapshot cost summed, not
 * recomputed); aggregation is independent of the retention cleanup (detail
 * rows may be deleted, aggregates remain).
 */
class UsageAggServiceTest {

    private UsageAggRepository repository;
    private UsageAggService service;
    private DailyAggTask task;

    @BeforeEach
    void setUp() {
        repository = mock(UsageAggRepository.class);
        when(repository.upsertAggForDate(any())).thenReturn(3);
        service = new UsageAggService(repository);
        task = new DailyAggTask(service);
    }

    @Test
    void aggregateCallsRepositoryForTheGivenDate() {
        LocalDate day = LocalDate.of(2026, 8, 6);
        int rows = service.aggregate(day);
        assertThat(rows).isEqualTo(3);
        verify(repository).upsertAggForDate(day);
    }

    @Test
    void dailyTaskAggregatesYesterdayAndToday() {
        task.aggregateDaily();
        LocalDate today = LocalDate.now();
        verify(repository).upsertAggForDate(today.minusDays(1));
        verify(repository).upsertAggForDate(today);
    }

    @Test
    void userDailyViewMapsRepositoryRows() {
        when(repository.findByUserAndRange(any(), any(), any()))
                .thenReturn(java.util.List.of(new UsageAggRepository.AggRow(
                        LocalDate.of(2026, 8, 5), null, null, "image", 10, 9, 100L, 200L,
                        new java.math.BigDecimal("12.500000"), "CNY")));
        var result = service.userDaily(UUID_1, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("cost")).isEqualTo(new java.math.BigDecimal("12.500000"));
        assertThat(result.get(0).get("requestCount")).isEqualTo(10);
    }

    private static final java.util.UUID UUID_1 = java.util.UUID.fromString("11111111-1111-1111-1111-111111111111");
}
