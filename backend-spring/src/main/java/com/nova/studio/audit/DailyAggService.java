package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * T25 (WIN-29) — daily aggregation orchestration: rebuilds the aggregation
 * window {@code [today - (lookback-1) .. today]} (delete + re-insert per day —
 * idempotent). Lookback defaults to 2 (yesterday + today) so late records for
 * the previous day are re-derived on the next run; aggregates survive detail
 * retention cleanup (A10).
 */
@Service
public class DailyAggService {

    private static final Logger log = LoggerFactory.getLogger(DailyAggService.class);

    private final UsageAggRepository repository;
    private final Clock clock;
    private final int lookbackDays;
    private final String timezone;

    @org.springframework.beans.factory.annotation.Autowired
    public DailyAggService(UsageAggRepository repository,
                           @Value("${NOVA_DAILY_AGG_LOOKBACK_DAYS:2}") int lookbackDays,
                           @Value("${NOVA_DAILY_AGG_TIMEZONE:Asia/Shanghai}") String timezone) {
        this(repository, Clock.systemDefaultZone(), lookbackDays, timezone);
    }

    /** Test constructor with a fixed clock. */
    DailyAggService(UsageAggRepository repository, Clock clock, int lookbackDays, String timezone) {
        this.repository = repository;
        this.clock = clock;
        this.lookbackDays = Math.max(1, lookbackDays);
        this.timezone = timezone == null || timezone.isBlank() ? "Asia/Shanghai" : timezone;
    }

    /** Rebuild the aggregation window; returns the number of days processed. */
    public int aggregateRecent() {
        LocalDate today = LocalDate.now(clock);
        int days = 0;
        for (int i = lookbackDays - 1; i >= 0; i--) {
            repository.aggregateDay(today.minusDays(i), timezone);
            days++;
        }
        log.info("[daily-agg] 本次聚合窗口 {} 天（截至 {}）", days, today);
        return days;
    }
}
