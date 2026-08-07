package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * T11 (WIN-28) — audit retention cleanup (A10): daily sweep deletes
 * {@code usage_records} older than {@code NOVA_AUDIT_RETENTION_DAYS} (default
 * 180). P1 daily aggregation is unaffected (separate table, F-33).
 */
@Component
public class AuditCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuditCleanupScheduler.class);

    private final UsageRecordRepository repository;
    private final AuditLogRepository auditLogRepository;
    private final long retentionDays;

    public AuditCleanupScheduler(UsageRecordRepository repository,
                                 AuditLogRepository auditLogRepository,
                                 @Value("${NOVA_AUDIT_RETENTION_DAYS:180}") long retentionDays) {
        this.repository = repository;
        this.auditLogRepository = auditLogRepository;
        this.retentionDays = Math.max(1, retentionDays);
    }

    /** 每日 03:30（UTC+8 凌晨）执行。 */
    @Scheduled(cron = "0 30 3 * * *")
    public void cleanup() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = repository.deleteBefore(cutoff);
        if (deleted > 0) {
            log.info("[audit-cleanup] 清理 {} 条过期用量明细（保留 {} 天）", deleted, retentionDays);
        }
        // T29 (A16): audit_log 与 usage 同保留期
        int auditDeleted = auditLogRepository.deleteBefore(cutoff);
        if (auditDeleted > 0) {
            log.info("[audit-cleanup] 清理 {} 条过期变更审计日志（保留 {} 天）", auditDeleted, retentionDays);
        }
    }
}
