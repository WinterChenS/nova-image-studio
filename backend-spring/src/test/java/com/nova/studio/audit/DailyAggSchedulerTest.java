package com.nova.studio.audit;

import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T25 (WIN-29) — daily aggregation scheduler: periodic run delegates to
 * {@link DailyAggService#aggregateRecent()} (window handled in the service).
 */
class DailyAggSchedulerTest {

    private final DailyAggService service = mock(DailyAggService.class);

    @Test
    void scheduledRunAggregatesRecentWindow() {
        DailyAggScheduler scheduler = new DailyAggScheduler(service);
        scheduler.aggregate();
        verify(service).aggregateRecent();
    }
}
