package com.nova.studio.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * T29 (WIN-29) — {@code audit_log} access: append-only change audit rows
 * (actor/action/target + JSON detail) with retention cleanup (same
 * {@code NOVA_AUDIT_RETENTION_DAYS} window as usage_records, A16). The detail
 * column never carries keys/credentials — enforced by the service-level
 * credential filter plus caller discipline.
 */
@Repository
public class AuditLogRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditLogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Append one audit row (actor may be null for system actions). */
    public void insert(UUID actorId, String action, String targetType, String targetId,
                       String detailJson, Instant createdAt) {
        jdbcTemplate.update("""
                INSERT INTO audit_log (actor_id, action, target_type, target_id, detail, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, actorId, action, targetType, targetId,
                detailJson == null ? "{}" : detailJson, Timestamp.from(createdAt));
    }

    /** Retention cleanup — same window as usage_records (A16: 同 usage 保留期). */
    public int deleteBefore(Instant cutoff) {
        return jdbcTemplate.update("DELETE FROM audit_log WHERE created_at < ?", Timestamp.from(cutoff));
    }
}
