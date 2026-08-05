package com.nova.studio.task;

import com.nova.studio.infra.RuntimeEnv;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.ws.WsQueueStatusProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Queue statistics (T1.6) — port of the Node backend's {@code getQueueStats}
 * ({@code backend/server.js}). Field-for-field compatible with the frontend
 * {@code NovaQueueStatus} contract ({@code ccode-task-client.ts}); served by
 * {@code GET /api/nova/queue-status} and the WS {@code queueStatus} pushes.
 *
 * <p>M2 (WIN-12): queue/limit knobs are read from the user's DB settings
 * ({@code limit.*}, per-user rows with fallback to the Node defaults) — the
 * {@code NOVA_MAX_QUEUE_SIZE}/{@code NOVA_RATE_LIMIT_*}/{@code NOVA_MAX_PENDING_TASKS_*}
 * env knobs no longer apply. Ops switches ({@code NOVA_ACCEPT_NEW_TASKS} /
 * {@code NOVA_REJECT_NEW_TASKS}) stay on .env (A.5-Q4/H4).
 */
@Service
public class QueueStatsService implements WsQueueStatusProvider {

    public static final int GLOBAL_CONCURRENCY = 50;

    private final TaskRepository repository;
    private final RuntimeEnv runtimeEnv;
    private final ShutdownFlag shutdownFlag;
    private final SettingsService settingsService;
    private final long ttlMs;

    public QueueStatsService(TaskRepository repository, RuntimeEnv runtimeEnv, ShutdownFlag shutdownFlag,
                             SettingsService settingsService,
                             @Value("${nova.task.ttl-ms:43200000}") long ttlMs) {
        this.repository = repository;
        this.runtimeEnv = runtimeEnv;
        this.shutdownFlag = shutdownFlag;
        this.settingsService = settingsService;
        this.ttlMs = ttlMs;
    }

    public long ttlMs() {
        return ttlMs;
    }

    /** Node getMaxServerConcurrency: clamp configured value to [1, 50]. */
    public int getMaxServerConcurrency() {
        int configured = runtimeEnv.getInt("NOVA_TASK_CONCURRENCY", GLOBAL_CONCURRENCY);
        return Math.max(1, Math.min(GLOBAL_CONCURRENCY, configured));
    }

    /** Limit config for a user, read from their {@code limit.*} DB settings. */
    public LimitConfig getLimitConfig(UUID userId) {
        return new LimitConfig(
                settingsService.getInt(userId, "limit.maxQueueSize", SettingsService.DEFAULT_MAX_QUEUE_SIZE),
                settingsService.getInt(userId, "limit.rateLimitWindowMs", SettingsService.DEFAULT_RATE_LIMIT_WINDOW_MS),
                settingsService.getInt(userId, "limit.maxRequestsPerIp", SettingsService.DEFAULT_MAX_REQUESTS_PER_IP),
                settingsService.getInt(userId, "limit.maxRequestsPerApiKey", SettingsService.DEFAULT_MAX_REQUESTS_PER_API_KEY),
                settingsService.getInt(userId, "limit.maxPendingTasksPerIp", SettingsService.DEFAULT_MAX_PENDING_TASKS_PER_IP),
                settingsService.getInt(userId, "limit.maxPendingTasksPerApiKey", SettingsService.DEFAULT_MAX_PENDING_TASKS_PER_API_KEY),
                settingsService.getInt(userId, "limit.retryAfterSeconds", SettingsService.DEFAULT_RETRY_AFTER_SECONDS));
    }

    /** Node getLimitConfig defaults (anonymous queue-status view). */
    public LimitConfig getLimitConfig() {
        return new LimitConfig(
                SettingsService.DEFAULT_MAX_QUEUE_SIZE,
                SettingsService.DEFAULT_RATE_LIMIT_WINDOW_MS,
                SettingsService.DEFAULT_MAX_REQUESTS_PER_IP,
                SettingsService.DEFAULT_MAX_REQUESTS_PER_API_KEY,
                SettingsService.DEFAULT_MAX_PENDING_TASKS_PER_IP,
                SettingsService.DEFAULT_MAX_PENDING_TASKS_PER_API_KEY,
                SettingsService.DEFAULT_RETRY_AFTER_SECONDS);
    }

    public record LimitConfig(int maxQueueSize, int rateLimitWindowMs, int maxRequestsPerIp,
                              int maxRequestsPerApiKey, int maxPendingTasksPerIp,
                              int maxPendingTasksPerApiKey, int retryAfterSeconds) {
    }

    /** Node isRejectNewTasksEnabled: NOVA_REJECT_NEW_TASKS truthy or NOVA_ACCEPT_NEW_TASKS false. */
    public boolean isRejectNewTasksEnabled() {
        String rejectSwitch = runtimeEnv.getString("NOVA_REJECT_NEW_TASKS", "").trim().toLowerCase();
        String acceptSwitch = runtimeEnv.getString("NOVA_ACCEPT_NEW_TASKS", "").trim().toLowerCase();
        return java.util.Set.of("1", "true", "yes", "on").contains(rejectSwitch)
                || acceptSwitch.equals("false") || acceptSwitch.equals("0");
    }

    public boolean acceptingNewTasks() {
        return !shutdownFlag.isShuttingDown() && !isRejectNewTasksEnabled();
    }

    @Override
    public Map<String, Object> getQueueStatus() {
        Map<String, Long> counts = repository.countByQueueStatuses();
        long processingCount = counts.getOrDefault(TaskRepository.STATUS_PROCESSING, 0L);
        long queuedCount = counts.getOrDefault(TaskRepository.STATUS_QUEUED, 0L)
                + counts.getOrDefault(TaskRepository.STATUS_LEGACY_QUEUED, 0L);
        long pendingCount = processingCount + queuedCount;
        LimitConfig config = getLimitConfig();
        boolean accepting = acceptingNewTasks();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("concurrencyLimit", GLOBAL_CONCURRENCY);
        stats.put("configuredConcurrency", getMaxServerConcurrency());
        stats.put("processingCount", processingCount);
        stats.put("queuedCount", queuedCount);
        stats.put("pendingCount", pendingCount);
        stats.put("maxQueueSize", config.maxQueueSize());
        stats.put("remainingQueueSlots", Math.max(0, config.maxQueueSize() - pendingCount));
        stats.put("displayConcurrency", Math.min(GLOBAL_CONCURRENCY, pendingCount));
        stats.put("displayQueued", Math.max(0, pendingCount - GLOBAL_CONCURRENCY));
        stats.put("acceptingNewTasks", accepting);
        stats.put("rateLimitWindowMs", config.rateLimitWindowMs());
        stats.put("rateLimitMaxRequestsPerIp", config.maxRequestsPerIp());
        stats.put("rateLimitMaxRequestsPerApiKey", config.maxRequestsPerApiKey());
        stats.put("retryAfterSeconds", config.retryAfterSeconds());
        if (!accepting) {
            stats.put("serverMessage", "服务器正在升级维护，暂不接受新任务。未完成任务将继续完成。");
        }
        return stats;
    }
}
