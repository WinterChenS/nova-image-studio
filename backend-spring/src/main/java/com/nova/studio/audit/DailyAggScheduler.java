package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * T25 (WIN-29) — daily aggregation scheduler: runs after the audit cleanup
 * window (03:45 UTC+8) so yesterday's detail is complete before aggregation.
 * Uses {@link DailyAggService#aggregateRecent()} — delete + rebuild, idempotent.
 */
@Component
public class DailyAggScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyAggScheduler.class);

    private final DailyAggService service;

    public DailyAggScheduler(DailyAggService service) {
        this.service = service;
    }

    /** 每日 03:45（UTC+8 凌晨，清理 03:30 之后）。 */
    @Scheduled(cron = "0 45 3 * * *")
    public void aggregate() {
        try {
            service.aggregateRecent();
        } catch (Exception e) {
            log.warn("[daily-agg] 聚合任务失败（下次运行重试）: {}", e.getMessage());
        }
    }
}
