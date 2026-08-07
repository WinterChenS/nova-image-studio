package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * T25 (WIN-29) — usage daily aggregation task (A10/F-33): every day at 03:00
 * (UTC+8 凌晨, before the 03:30 retention cleanup) aggregates yesterday AND
 * today into {@code usage_daily_agg}. Aggregates are independent of the
 * cleanup — detail rows may be deleted, daily aggregates remain.
 */
@Component
public class DailyAggTask {

    private static final Logger log = LoggerFactory.getLogger(DailyAggTask.class);

    private final UsageAggService aggService;

    public DailyAggTask(UsageAggService aggService) {
        this.aggService = aggService;
    }

    /** 每日 03:00 执行：聚合昨天 + 今天（含 UTC 日期边界安全余量）。 */
    @Scheduled(cron = "0 0 3 * * *")
    public void aggregateDaily() {
        LocalDate today = LocalDate.now();
        int yesterday = aggService.aggregate(today.minusDays(1));
        int todayRows = aggService.aggregate(today);
        if (yesterday + todayRows > 0) {
            log.info("[usage-agg] 日聚合完成：{}={} 行，{}={} 行", today.minusDays(1), yesterday, today, todayRows);
        }
    }
}
