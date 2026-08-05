package com.nova.studio.task;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL task storage (T1.1) — port of the Node backend's SQLite
 * {@code tasks}/{@code task_items} tables with the user-confirmed Q1 column
 * {@code user_id} (nullable = system/migrated ownership). WIN-16 (ADR-11):
 * migrated from JdbcTemplate to MyBatis-Plus ({@link TaskMapper} +
 * {@link TaskEntity}); simple lookups use BaseMapper, every state-machine SQL
 * is kept verbatim so the Node semantics ('排队中' + 'queued' both counted as
 * queued; expires_at comparisons in UTC) do not drift. Public signatures and
 * the {@link TaskRow} record are unchanged (strategy A).
 */
@Repository
public class TaskRepository {

    public static final String STATUS_QUEUED = "排队中";
    public static final String STATUS_LEGACY_QUEUED = "queued";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";

    /** Public row shape (service/test contract, unchanged). */
    public record TaskRow(String id, String userId, String status, String mode,
                          String requestJson, String resultJson, String error, String warning,
                          Instant createdAt, Instant completedAt, Instant expiresAt) {
    }

    private final TaskMapper mapper;

    public TaskRepository(TaskMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<TaskRow> findById(String id) {
        return Optional.ofNullable(mapper.selectById(id)).map(TaskRepository::toRow);
    }

    public boolean exists(String id) {
        return mapper.selectById(id) != null;
    }

    @Transactional
    public void insertTaskAndItems(String id, UUID userId, String status, String mode,
                                   String requestJson, String createdAt, int itemCount) {
        Instant created = Instant.parse(createdAt);
        mapper.insertTask(id, userId, status, mode, requestJson, created);
        for (int i = 0; i < itemCount; i++) {
            mapper.insertItem(id, i, status, created);
        }
    }

    public void updateStatus(String id, String status) {
        mapper.updateStatus(id, status);
    }

    public void updateStatusProcessing(String id, String status, String createdAtIso) {
        mapper.updateStatus(id, status);
    }

    public void updateItemStatus(String taskId, int itemIndex, String status, String completedAtIso) {
        mapper.updateItemStatus(taskId, itemIndex, status, Instant.parse(completedAtIso));
    }

    public void updateItemCreatedAt(String taskId, int itemIndex, String createdAtIso) {
        mapper.updateItemCreatedAt(taskId, itemIndex, Instant.parse(createdAtIso));
    }

    /** Node runTask: items flip to processing with created_at refreshed. */
    public void updateItemProcessing(String taskId, int itemIndex, String createdAtIso) {
        mapper.updateItemProcessing(taskId, itemIndex, STATUS_PROCESSING, Instant.parse(createdAtIso));
    }

    /** Marks a task completed with result_json / warning / completed_at / expires_at. */
    public void completeTask(String id, String resultJson, String warning, String completedAtIso, String expiresAtIso) {
        mapper.completeTask(id, STATUS_COMPLETED, resultJson, warning,
                Instant.parse(completedAtIso), Instant.parse(expiresAtIso));
    }

    /** Marks a task failed with error / completed_at / expires_at. */
    public void failTask(String id, String error, String completedAtIso, String expiresAtIso) {
        mapper.failTask(id, STATUS_FAILED, error,
                Instant.parse(completedAtIso), Instant.parse(expiresAtIso));
    }

    /** Marks interrupted (queued/processing) tasks failed — Node initDatabase semantics. */
    public List<String> markInterruptedTasksFailed(String error, String completedAtIso, String expiresAtIso) {
        List<String> interrupted = mapper.findInterrupted(STATUS_QUEUED, STATUS_PROCESSING);
        mapper.failInterrupted(STATUS_FAILED, error,
                Instant.parse(completedAtIso), Instant.parse(expiresAtIso),
                STATUS_QUEUED, STATUS_PROCESSING);
        return interrupted;
    }

    /** Normalizes legacy 'queued' rows to '排队中' — Node startup behavior. */
    public void normalizeLegacyQueued() {
        mapper.normalizeTaskStatus(STATUS_QUEUED, STATUS_LEGACY_QUEUED);
        mapper.normalizeItemStatus(STATUS_QUEUED, STATUS_LEGACY_QUEUED);
    }

    public void updateExpiresAt(String id, Instant expiresAt) {
        mapper.updateExpiresAt(id, expiresAt);
    }

    public void updateItemImageData(String taskId, int itemIndex, String status, String imageDataJson, String completedAtIso) {
        mapper.updateItemImageData(taskId, itemIndex, status, imageDataJson, Instant.parse(completedAtIso));
    }

    public void updateItemError(String taskId, int itemIndex, String status, String error, String completedAtIso) {
        mapper.updateItemError(taskId, itemIndex, status, error, Instant.parse(completedAtIso));
    }

    /** Queue stats grouping — Node getQueueStats SQL (statuses '排队中'/'queued'/'processing'). */
    public Map<String, Long> countByQueueStatuses() {
        List<Map<String, Object>> rows = mapper.countByQueueStatuses(
                STATUS_QUEUED, STATUS_LEGACY_QUEUED, STATUS_PROCESSING);
        LinkedHashMap<String, Long> counts = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            counts.put(String.valueOf(row.get("status")), ((Number) row.get("count")).longValue());
        }
        return counts;
    }

    public List<String> findExpired(Instant now) {
        return mapper.findExpired(now);
    }

    @Transactional
    public void deleteTaskAndItems(String id) {
        mapper.deleteItems(id);
        mapper.deleteTask(id);
    }

    private static TaskRow toRow(TaskEntity e) {
        return new TaskRow(e.getId(), e.getUserId(), e.getStatus(), e.getMode(),
                e.getRequestJson(), e.getResultJson(), e.getError(), e.getWarning(),
                e.getCreatedAt(), e.getCompletedAt(), e.getExpiresAt());
    }
}
