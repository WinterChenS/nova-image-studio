package com.nova.studio.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T11 (WIN-28) — audit retention cleanup: NOVA_AUDIT_RETENTION_DAYS (default
 * 180) daily sweep deletes rows older than the cutoff (A10).
 */
class AuditCleanupSchedulerTest {

    private UsageRecordRepository repository;
    private AuditCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(UsageRecordRepository.class);
        scheduler = new AuditCleanupScheduler(repository, 180);
    }

    @Test
    void cleanupDeletesRowsOlderThanRetention() {
        when(repository.deleteBefore(any())).thenReturn(37);
        scheduler.cleanup();
        verify(repository).deleteBefore(any());
    }

    @Test
    void cutoffIsRetentionDaysInThePast() {
        when(repository.deleteBefore(any())).thenReturn(0);
        scheduler.cleanup();
        var captor = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).deleteBefore(captor.capture());
        long diffDays = java.time.Duration.between(captor.getValue(), Instant.now()).toDays();
        assertThat(diffDays).isBetween(179L, 180L);
    }
}
