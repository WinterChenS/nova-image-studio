package com.nova.studio.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * PostgreSQL task storage (T1.1) — port of the Node backend's SQLite
 * {@code tasks}/{@code task_items} tables with the user-confirmed Q1 column
 * {@code user_id} (nullable = system/migrated ownership). Plain JDBC keeps the
 * queries byte-compatible with the Node semantics ('排队中' + 'queued' both
 * counted as queued; expires_at comparisons in UTC).
 */
@Repository
public class TaskRepository {

    public static final String STATUS_QUEUED = "排队中";
    public static final String STATUS_LEGACY_QUEUED = "queued";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Full task row (no image data). */
    public record TaskRow(String id, String userId, String status, String mode,
                          String requestJson, String resultJson, String error, String warning,
                          Instant createdAt, Instant completedAt, Instant expiresAt) {
    }

    private static final RowMapper<TaskRow> ROW_MAPPER = (rs, rowNum) -> new TaskRow(
            rs.getString("id"),
            rs.getString("user_id"),
            rs.getString("status"),
            rs.getString("mode"),
            rs.getString("request_json"),
            rs.getString("result_json"),
            rs.getString("error"),
            rs.getString("warning"),
            toInstant(rs, "created_at"),
            toInstant(rs, "completed_at"),
            toInstant(rs, "expires_at"));

    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    public Optional<TaskRow> findById(String id) {
        List<TaskRow> rows = jdbc.query("SELECT * FROM tasks WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public boolean exists(String id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }

    @Transactional
    public void insertTaskAndItems(String id, String userId, String status, String mode,
                                   String requestJson, String createdAt, int itemCount) {
        jdbc.update("""
                INSERT INTO tasks (id, user_id, status, mode, request_json, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                """, id, userId, status, mode, requestJson, OffsetDateTime.ofInstant(Instant.parse(createdAt), java.time.ZoneOffset.UTC));
        Instant created = Instant.parse(createdAt);
        for (int i = 0; i < itemCount; i++) {
            jdbc.update("""
                    INSERT INTO task_items (task_id, item_index, status, created_at)
                    VALUES (?, ?, ?, ?)
                    """, id, i, status, OffsetDateTime.ofInstant(created, java.time.ZoneOffset.UTC));
        }
    }

    public void updateStatus(String id, String status) {
        jdbc.update("UPDATE tasks SET status = ? WHERE id = ?", status, id);
    }

    public void updateStatusProcessing(String id, String status, String createdAtIso) {
        jdbc.update("UPDATE tasks SET status = ? WHERE id = ?", status, id);
    }

    public void updateItemStatus(String taskId, int itemIndex, String status, String completedAtIso) {
        jdbc.update("""
                UPDATE task_items SET status = ?, completed_at = ? WHERE task_id = ? AND item_index = ?
                """, status, OffsetDateTime.ofInstant(Instant.parse(completedAtIso), java.time.ZoneOffset.UTC),
                taskId, itemIndex);
    }

    public void updateItemCreatedAt(String taskId, int itemIndex, String createdAtIso) {
        jdbc.update("""
                UPDATE task_items SET created_at = ? WHERE task_id = ? AND item_index = ?
                """, OffsetDateTime.ofInstant(Instant.parse(createdAtIso), java.time.ZoneOffset.UTC), taskId, itemIndex);
    }

    /** Node runTask: items flip to processing with created_at refreshed. */
    public void updateItemProcessing(String taskId, int itemIndex, String createdAtIso) {
        jdbc.update("""
                UPDATE task_items SET status = ?, created_at = ? WHERE task_id = ? AND item_index = ?
                """, STATUS_PROCESSING, OffsetDateTime.ofInstant(Instant.parse(createdAtIso), java.time.ZoneOffset.UTC),
                taskId, itemIndex);
    }

    /** Marks a task completed with result_json / warning / completed_at / expires_at. */
    public void completeTask(String id, String resultJson, String warning, String completedAtIso, String expiresAtIso) {
        jdbc.update("""
                UPDATE tasks SET status = ?, result_json = ?::jsonb, warning = ?, completed_at = ?, expires_at = ?
                WHERE id = ?
                """, STATUS_COMPLETED, resultJson, warning,
                OffsetDateTime.ofInstant(Instant.parse(completedAtIso), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(Instant.parse(expiresAtIso), java.time.ZoneOffset.UTC),
                id);
    }

    /** Marks a task failed with error / completed_at / expires_at. */
    public void failTask(String id, String error, String completedAtIso, String expiresAtIso) {
        jdbc.update("""
                UPDATE tasks SET status = ?, error = ?, completed_at = ?, expires_at = ?
                WHERE id = ?
                """, STATUS_FAILED, error,
                OffsetDateTime.ofInstant(Instant.parse(completedAtIso), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(Instant.parse(expiresAtIso), java.time.ZoneOffset.UTC),
                id);
    }

    /** Marks interrupted (queued/processing) tasks failed — Node initDatabase semantics. */
    public List<String> markInterruptedTasksFailed(String error, String completedAtIso, String expiresAtIso) {
        List<String> interrupted = jdbc.queryForList(
                "SELECT id FROM tasks WHERE status IN (?, ?)", String.class, STATUS_QUEUED, STATUS_PROCESSING);
        jdbc.update("""
                UPDATE tasks SET status = ?, error = ?, completed_at = ?, expires_at = ?
                WHERE status IN (?, ?)
                """, STATUS_FAILED, error,
                OffsetDateTime.ofInstant(Instant.parse(completedAtIso), java.time.ZoneOffset.UTC),
                OffsetDateTime.ofInstant(Instant.parse(expiresAtIso), java.time.ZoneOffset.UTC),
                STATUS_QUEUED, STATUS_PROCESSING);
        return interrupted;
    }

    /** Normalizes legacy 'queued' rows to '排队中' — Node startup behavior. */
    public void normalizeLegacyQueued() {
        jdbc.update("UPDATE tasks SET status = ? WHERE status = ?", STATUS_QUEUED, STATUS_LEGACY_QUEUED);
        jdbc.update("UPDATE task_items SET status = ? WHERE status = ?", STATUS_QUEUED, STATUS_LEGACY_QUEUED);
    }

    public void updateExpiresAt(String id, Instant expiresAt) {
        jdbc.update("UPDATE tasks SET expires_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC), id);
    }

    public void updateItemImageData(String taskId, int itemIndex, String status, String imageDataJson, String completedAtIso) {
        jdbc.update("""
                UPDATE task_items SET status = ?, image_data = ?, completed_at = ?
                WHERE task_id = ? AND item_index = ?
                """, status, imageDataJson,
                OffsetDateTime.ofInstant(Instant.parse(completedAtIso), java.time.ZoneOffset.UTC),
                taskId, itemIndex);
    }

    public void updateItemError(String taskId, int itemIndex, String status, String error, String completedAtIso) {
        jdbc.update("""
                UPDATE task_items SET status = ?, error = ?, completed_at = ?
                WHERE task_id = ? AND item_index = ?
                """, status, error,
                OffsetDateTime.ofInstant(Instant.parse(completedAtIso), java.time.ZoneOffset.UTC),
                taskId, itemIndex);
    }

    /** Queue stats grouping — Node getQueueStats SQL (statuses '排队中'/'queued'/'processing'). */
    public Map<String, Long> countByQueueStatuses() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, COUNT(*) AS count FROM tasks
                WHERE status IN (?, ?, ?)
                GROUP BY status
                """, STATUS_QUEUED, STATUS_LEGACY_QUEUED, STATUS_PROCESSING);
        java.util.LinkedHashMap<String, Long> counts = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(String.valueOf(row.get("status")), ((Number) row.get("count")).longValue());
        }
        return counts;
    }

    public List<String> findExpired(Instant now) {
        return jdbc.queryForList(
                "SELECT id FROM tasks WHERE expires_at IS NOT NULL AND expires_at <= ?",
                String.class, OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC));
    }

    @Transactional
    public void deleteTaskAndItems(String id) {
        jdbc.update("DELETE FROM task_items WHERE task_id = ?", id);
        jdbc.update("DELETE FROM tasks WHERE id = ?", id);
    }
}
