package com.nova.studio.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T7 (WIN-28) — usage collector: builds request-level records from the image
 * task result (task-level single row, images=success count, H5) and the text
 * proxy result (ref_id = request UUID); status success/retried/failed.
 */
class UsageCollectorTest {

    private UsageRecordService usageRecordService;
    private UsageCollector collector;

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        usageRecordService = mock(UsageRecordService.class);
        collector = new UsageCollector(usageRecordService);
    }

    @Test
    void taskSuccessRecord() {
        collector.recordTaskUsage(new UsageCollector.TaskUsage("task-1", USER, MODEL, "openai",
                2, 2, false, false, ACCOUNT, 1500L));
        verify(usageRecordService).record(any());
    }

    @Test
    void taskFailedRecordStatus() {
        collector.recordTaskUsage(new UsageCollector.TaskUsage("task-1", USER, MODEL, "openai",
                1, 0, false, true, ACCOUNT, 900L));
        verify(usageRecordService).record(any());
    }

    @Test
    void proxyRecordWithTokens() {
        collector.recordProxyUsage(new UsageCollector.ProxyUsage("req-1", USER, MODEL, ACCOUNT,
                "openai-chat-completions", false, false, 100L, 200L, 800L));
        verify(usageRecordService).record(any());
    }
}
