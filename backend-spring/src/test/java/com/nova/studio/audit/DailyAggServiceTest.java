package com.nova.studio.audit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T25 (WIN-29) — daily aggregation service: rebuilds {@code usage_daily_agg}
 * for a lookback window (delete + re-insert per day, idempotent; cost uses the
 * detail snapshot sum — never recomputed, A10/F-33).
 */
class DailyAggServiceTest {

    private static final ZoneId TZ = ZoneId.of("Asia/Shanghai");
    private static final Instant NOW = Instant.parse("2026-08-07T04:00:00Z"); // 2026-08-07 12:00 +08

    private final UsageAggRepository repository = mock(UsageAggRepository.class);

    @Test
    void aggregatesYesterdayAndTodayByDefaultLookback() {
        DailyAggService service = new DailyAggService(repository, Clock.fixed(NOW, TZ), 2, "Asia/Shanghai");
        int days = service.aggregateRecent();
        assertThat(days).isEqualTo(2);
        verify(repository).aggregateDay(eq(LocalDate.of(2026, 8, 6)), eq("Asia/Shanghai"));
        verify(repository).aggregateDay(eq(LocalDate.of(2026, 8, 7)), eq("Asia/Shanghai"));
    }

    @Test
    void aggregatesConfiguredLookbackWindow() {
        DailyAggService service = new DailyAggService(repository, Clock.fixed(NOW, TZ), 5, "Asia/Shanghai");
        int days = service.aggregateRecent();
        assertThat(days).isEqualTo(5);
        for (int i = 0; i < 5; i++) {
            verify(repository).aggregateDay(eq(LocalDate.of(2026, 8, 7 - i)), eq("Asia/Shanghai"));
        }
    }

    @Test
    void clampsInvalidLookbackToOneDay() {
        DailyAggService service = new DailyAggService(repository, Clock.fixed(NOW, TZ), 0, "Asia/Shanghai");
        assertThat(service.aggregateRecent()).isEqualTo(1);
        verify(repository).aggregateDay(eq(LocalDate.of(2026, 8, 7)), eq("Asia/Shanghai"));
    }
}
