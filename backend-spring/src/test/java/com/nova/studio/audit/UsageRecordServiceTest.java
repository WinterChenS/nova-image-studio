package com.nova.studio.audit;

import com.nova.studio.accountpool.PricingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T7 (WIN-28) — usage record service: cost snapshot at write time (B3/A8:
 * 单次价 + tokens × 每 token 单价), idempotent INSERT ON CONFLICT (R2/A20),
 * async bounded queue with flush for tests.
 */
class UsageRecordServiceTest {

    private UsageRecordMapper mapper;
    private PricingService pricingService;
    private UsageRecordService service;

    private static final UUID USER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        mapper = mock(UsageRecordMapper.class);
        pricingService = mock(PricingService.class);
        when(pricingService.computeCost(any(), any(), any(), any())).thenReturn(new BigDecimal("0.8"));
        service = new UsageRecordService(mapper, pricingService, 10_000);
    }

    private UsageRecordService.UsageRecord rec() {
        return new UsageRecordService.UsageRecord(USER, ACCOUNT, MODEL, "openai", "image",
                "task", "task-1", "success", 100L, 200L, 2, "CNY", 1500L);
    }

    @Test
    void recordSyncComputesSnapshotCostAndInserts() {
        service.recordSync(rec());
        verify(mapper).insertIgnore(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(pricingService).computeCost(MODEL, 100L, 200L, "CNY");
    }

    @Test
    void asyncRecordFlushesToInsert() {
        service.record(rec());
        service.flush();
        verify(mapper).insertIgnore(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void idempotentConflictIsAccepted() {
        when(mapper.insertIgnore(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any())).thenReturn(0);   // ON CONFLICT DO NOTHING
        int result = service.recordSync(rec());
        assertThat(result).isZero();   // 不抛错、不重复计费（A20）
    }
}
