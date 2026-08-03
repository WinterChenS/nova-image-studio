package com.nova.studio.task;

import com.nova.studio.infra.RuntimeEnv;
import com.nova.studio.ws.WsQueueStatusProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Queue statistics (T1.6) — port of the Node backend's {@code getQueueStats}
 * ({@code backend/server.js}). Field-for-field compatible with the frontend
 * {@code NovaQueueStatus} contract ({@code ccode-task-client.ts}); served by
 * {@code GET /api/nova/queue-status} and the WS {@code queueStatus} pushes.
 *
 * <p>Concurrency/limit knobs are read hot from the runtime env (1s TTL) exactly
 * like the Node backend: {@code NOVA_TASK_CONCURRENCY},
 * {@code NOVA_MAX_QUEUE_SIZE}, {@code NOVA_RATE_LIMIT_*},
 * {@code NOVA_MAX_PENDING_TASKS_*}, {@code NOVA_ACCEPT_NEW_TASKS} /
 * {@code NOVA_REJECT_NEW_TASKS} (A.5-Q4/H4: ops switches stay on .env).
 */
@Service
public class QueueStatsService implements WsQueueStatusProvider {

    public static final int GLOBAL_CONCURRENCY = 50;

    private final TaskRepository repository;
    private final RuntimeEnv runtimeEnv;
    private final ShutdownFlag shutdownFlag;
    private final long ttlMs;

    public QueueStatsService(TaskRepository repository, RuntimeEnv runtimeEnv, ShutdownFlag shutdownFlag,
                             @Value("${nova.task.ttl-ms:43200000}") long ttlMs) {
        this.repository = repository;
        this.runtimeEnv = runtimeEnv;
        this.shutdownFlag = shutdownFlag;
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

    /** Limit config read hot from .env — Node getLimitConfig defaults. */
    public LimitConfig getLimitConfig() {
        return new LimitConfig(
                runtimeEnv.getInt("NOVA_MAX_QUEUE_SIZE", 200),
                runtimeEnv.getInt("NOVA_RATE_LIMIT_WINDOW_MS", 60_000),
                runtimeEnv.getInt("NOVA_RATE_LIMIT_MAX_REQUESTS_PER_IP", 20),
                runtimeEnv.getInt("NOVA_RATE_LIMIT_MAX_REQUESTS_PER_API_KEY", 20),
                runtimeEnv.getInt("NOVA_MAX_PENDING_TASKS_PER_IP", 20),
                runtimeEnv.getInt("NOVA_MAX_PENDING_TASKS_PER_API_KEY", 10),
                runtimeEnv.getInt("NOVA_RATE_LIMIT_RETRY_AFTER_SECONDS", 30));
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
