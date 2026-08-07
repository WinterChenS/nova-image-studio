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
    public record TaskRow(String id, String userId, String projectId, String status, String mode,
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
    public void insertTaskAndItems(String id, UUID userId, String projectId, String status, String mode,
                                   String requestJson, String createdAt, int itemCount) {
        Instant created = Instant.parse(createdAt);
        mapper.insertTask(id, userId, projectId, status, mode, requestJson, created);
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

    /** WIN-22: count a user's tasks in a project (project stats F-6).
     *  B-1: tasks.user_id 为 UUID 列（V2），必须 {0}::uuid 绑定，否则 PG 报
     *  operator does not exist: uuid = character varying。 */
    public long countByUserAndProject(UUID userId, String projectId) {
        return mapper.selectCount(new LambdaQueryWrapper<TaskEntity>()
                .apply("user_id = {0}::uuid", userId.toString())
                .eq(TaskEntity::getProjectId, projectId));
    }

    /** WIN-22: last task activity in a project (max created_at). */
    public Instant lastActivityByProject(UUID userId, String projectId) {
        List<TaskEntity> rows = mapper.selectList(new LambdaQueryWrapper<TaskEntity>()
                .apply("user_id = {0}::uuid", userId.toString())
                .eq(TaskEntity::getProjectId, projectId)
                .orderByDesc(TaskEntity::getCreatedAt)
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0).getCreatedAt();
    }

    /** WIN-22: project delete cascade — tasks fall back to 未分类 (NULL, G-4). */
    public void clearProjectId(String projectId) {
        TaskEntity patch = new TaskEntity();
        patch.setProjectId(null);
        mapper.update(patch, new LambdaQueryWrapper<TaskEntity>()
                .eq(TaskEntity::getProjectId, projectId));
    }

    /** WIN-22 (D.3): one-click assign (F-5) — {@code projectId} null = 未分类. */
    public void updateProjectId(String taskId, String projectId) {
        TaskEntity patch = new TaskEntity();
        patch.setProjectId(projectId);
        mapper.update(patch, new LambdaQueryWrapper<TaskEntity>().eq(TaskEntity::getId, taskId));
    }

    /** WIN-22 (D.3/Q1): owner-scoped task history — filters + pagination (newest first).
     *  B-1/B-3: user_id 用 {0}::uuid 绑定（UUID 列）；计数 wrapper 与列表 wrapper
     *  分离，避免 COUNT 查询携带 ORDER BY（PG 报 must appear in GROUP BY）。 */
    public TaskPage searchByUser(UUID userId, String projectId, String status, int page, int size) {
        LambdaQueryWrapper<TaskEntity> countWrapper = new LambdaQueryWrapper<>();
        applyTaskFilters(countWrapper, userId, projectId, status);
        Long total = mapper.selectCount(countWrapper);

        LambdaQueryWrapper<TaskEntity> wrapper = new LambdaQueryWrapper<>();
        applyTaskFilters(wrapper, userId, projectId, status);
        wrapper.orderByDesc(TaskEntity::getCreatedAt);
        int offset = Math.max(0, (page - 1) * size);
        wrapper.last("LIMIT " + size + " OFFSET " + offset);
        List<TaskRow> items = mapper.selectList(wrapper).stream()
                .map(TaskRepository::toRow).toList();
        return new TaskPage(items, total == null ? 0 : total);
    }

    /** Shared owner/filter conditions for task search (count + list wrappers). */
    static void applyTaskFilters(LambdaQueryWrapper<TaskEntity> wrapper, UUID userId,
                                 String projectId, String status) {
        wrapper.apply("user_id = {0}::uuid", userId.toString());
        if (projectId != null && !projectId.isBlank()) {
            if ("__unclassified__".equals(projectId)) {
                wrapper.isNull(TaskEntity::getProjectId);
            } else {
                wrapper.eq(TaskEntity::getProjectId, projectId);
            }
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(TaskEntity::getStatus, status);
        }
    }

    public record TaskPage(List<TaskRow> items, long total) {
    }

    @Transactional
    public void deleteTaskAndItems(String id) {
        mapper.deleteItems(id);
        mapper.deleteTask(id);
    }

    private static TaskRow toRow(TaskEntity e) {
        return new TaskRow(e.getId(), e.getUserId(), e.getProjectId(), e.getStatus(), e.getMode(),
                e.getRequestJson(), e.getResultJson(), e.getError(), e.getWarning(),
                e.getCreatedAt(), e.getCompletedAt(), e.getExpiresAt());
    }
}
