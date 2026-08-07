package com.nova.studio.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * WIN-29 (V5) — {@code audit_log} retention cleanup (T29/A16): same retention
 * as {@code usage_records} ({@code NOVA_AUDIT_RETENTION_DAYS}); called by
 * {@link AuditCleanupScheduler} alongside the usage sweep.
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int deleteBefore(Instant cutoff) {
        return jdbcTemplate.update("DELETE FROM audit_log WHERE created_at < ?", Timestamp.from(cutoff));
    }
}
