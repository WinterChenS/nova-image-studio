package com.nova.studio.task;

import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.NormalizedError;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Task queue with slot concurrency (T1.2) — faithful port of the Node backend's
 * {@code drainQueue} / {@code runTask} / {@code generateSingleImage}
 * ({@code backend/server.js}):
 * <ul>
 *   <li>concurrency = image <b>slots</b> ({@code parallelCount} per task), cap 50
 *       ({@code NOVA_TASK_CONCURRENCY}, clamped to [1,50]);</li>
 *   <li>oversized exclusive run: a task whose slots exceed the cap may run alone
 *       when the queue is idle (otherwise it would never be scheduled);</li>
 *   <li>state machine: '排队中' → processing → completed|failed (expired derived
 *       on read); items updated independently;</li>
 *   <li>runtime state (apiKey / ref images / source counters) held in memory —
 *       the same way the Node backend keeps {@code apiKeys}/{@code taskRefImages}
 *       out of the DB; lost on restart, and stale tasks are failed at startup.</li>
 * </ul>
 */
@Service
public class TaskQueueService {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);

    private final TaskRepository repository;
    private final ImageGenService imageGenService;
    private final ImageStorageService imageStorageService;
    private final TaskEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final QueueStatsService queueStatsService;
    private final TaskMetrics taskMetrics;
    private final long ttlMs;
    private final long requestTimeoutMs;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private int activeSlots = 0;

    // Runtime state mirrors (Node: apiKeys / taskRefImages / taskSources / pending counters)
    private final Map<String, String> apiKeys = new ConcurrentHashMap<>();
    private final Map<String, List<TaskRequest.ImageReference>> taskRefImages = new ConcurrentHashMap<>();
    private final Map<String, Source> taskSources = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingCountByIp = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingCountByApiKeyHash = new ConcurrentHashMap<>();

    public record Source(String ip, String apiKeyHash) {
    }

    public TaskQueueService(TaskRepository repository,
                            ImageGenService imageGenService,
                            ImageStorageService imageStorageService,
                            TaskEventBroadcaster broadcaster,
                            ObjectMapper objectMapper,
                            QueueStatsService queueStatsService,
                            TaskMetrics taskMetrics,
                            @Value("${nova.task.ttl-ms:43200000}") long ttlMs,
                            @Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs) {
        this.repository = repository;
        this.imageGenService = imageGenService;
        this.imageStorageService = imageStorageService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.queueStatsService = queueStatsService;
        this.taskMetrics = taskMetrics;
        this.ttlMs = ttlMs;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    // ===== runtime state =====

    public void registerRuntimeState(String taskId, String apiKey, List<TaskRequest.ImageReference> images,
                                     Source source) {
        if (apiKey != null) {
            apiKeys.put(taskId, apiKey);
        }
        if (images != null) {
            taskRefImages.put(taskId, images);
        }
        if (source != null) {
            taskSources.put(taskId, source);
            if (source.ip() != null) {
                pendingCountByIp.merge(source.ip(), 1, Integer::sum);
            }
            if (source.apiKeyHash() != null) {
                pendingCountByApiKeyHash.merge(source.apiKeyHash(), 1, Integer::sum);
            }
        }
    }

    public int getPendingCountByIp(String ip) {
        return ip == null ? 0 : pendingCountByIp.getOrDefault(ip, 0);
    }

    public int getPendingCountByApiKeyHash(String hash) {
        return hash == null ? 0 : pendingCountByApiKeyHash.getOrDefault(hash, 0);
    }

    public void cleanupTaskRuntimeState(String taskId) {
        Source source = taskSources.remove(taskId);
        if (source != null) {
            decrement(pendingCountByIp, source.ip());
            decrement(pendingCountByApiKeyHash, source.apiKeyHash());
        }
        apiKeys.remove(taskId);
        taskRefImages.remove(taskId);
    }

    private static void decrement(Map<String, Integer> counters, String key) {
        if (key == null) {
            return;
        }
        counters.computeIfPresent(key, (k, v) -> v <= 1 ? null : v - 1);
    }

    // ===== enqueue / drain =====

    /** Node createTask tail: push to queue, broadcast, drain. */
    public void enqueue(String taskId) {
        synchronized (queue) {
            queue.addLast(taskId);
        }
        broadcaster.broadcastTask(taskId);
        broadcaster.broadcastQueueStatus();
        drainQueue();
    }

    /** Slot-based dispatcher — port of Node drainQueue. */
    public void drainQueue() {
        while (true) {
            String taskId;
            int slots;
            synchronized (queue) {
                if (queue.isEmpty()) {
                    return;
                }
                taskId = queue.getFirst();
                slots = imageSlots(taskId);
                int maxConcurrency = queueStatsService.getMaxServerConcurrency();
                boolean fits = activeSlots + slots <= maxConcurrency;
                boolean oversizedAlone = activeSlots == 0 && slots > maxConcurrency;
                if (!fits && !oversizedAlone) {
                    return;
                }
                queue.removeFirst();
                activeSlots += slots;
            }
            final String id = taskId;
            final int taskSlots = slots;
            Future<?> future = executor.submit(() -> runTask(id, taskSlots));
            runningTasks.put(taskId, future);
        }
    }

    private int imageSlots(String taskId) {
        return repository.findById(taskId)
                .flatMap(row -> parseRequest(row.requestJson()))
                .map(r -> Math.max(1, r.parallelCount()))
                .orElse(1);
    }

    private java.util.Optional<TaskRequest> parseRequest(String requestJson) {
        try {
            return java.util.Optional.of(TaskRequest.fromStored(objectMapper.readTree(requestJson), null));
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /** Runs one task: processing → parallel item generation → completed/failed. */
    public void runTask(String taskId, int slots) {
        try {
            executeTask(taskId);
        } catch (Exception e) {
            log.error("[queue] task run failed taskId={}", taskId, e);
        } finally {
            synchronized (queue) {
                activeSlots -= slots;
            }
            runningTasks.remove(taskId);
            drainQueue();
        }
    }

    private void executeTask(String taskId) {
        var rowOpt = repository.findById(taskId);
        String apiKey = apiKeys.get(taskId);
        if (rowOpt.isEmpty() || apiKey == null
                || !List.of(TaskRepository.STATUS_QUEUED, TaskRepository.STATUS_LEGACY_QUEUED)
                .contains(rowOpt.get().status())) {
            cleanupTaskRuntimeState(taskId);
            return;
        }
        TaskRequest request = parseRequest(rowOpt.get().requestJson()).orElse(null);
        if (request == null) {
            repository.failTask(taskId, "任务请求解析失败", Instant.now().toString(), Instant.now().plusMillis(ttlMs).toString());
            taskMetrics.taskFailed();
            cleanupTaskRuntimeState(taskId);
            broadcaster.broadcastTask(taskId);
            broadcaster.broadcastQueueStatus();
            return;
        }
        List<TaskRequest.ImageReference> refImages = taskRefImages.get(taskId);
        if (refImages != null && !refImages.isEmpty()) {
            request = new TaskRequest(request.mode(), request.protocol(), request.baseUrl(), request.prompt(),
                    request.outputSize(), request.customSize(), request.aspectRatio(), request.temperature(),
                    request.model(), request.gptImageQuality(), request.gptImageStyle(), request.gptImageBackground(),
                    request.parallelCount(), refImages);
        }

        repository.updateStatus(taskId, TaskRepository.STATUS_PROCESSING);
        taskMetrics.taskProcessing();
        broadcaster.broadcastTask(taskId);
        broadcaster.broadcastQueueStatus();

        String processingAt = Instant.now().toString();
        for (int index = 0; index < request.parallelCount(); index++) {
            repository.updateItemProcessing(taskId, index, processingAt);
        }

        // Parallel item generation (Node Promise.allSettled).
        List<Future<ItemResult>> futures = new ArrayList<>();
        final TaskRequest req = request;
        final String key = apiKey;
        for (int index = 0; index < request.parallelCount(); index++) {
            final int idx = index;
            futures.add(executor.submit(() -> generateSingleImage(key, req, taskId, idx)));
        }
        List<String> images = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Future<ItemResult> future : futures) {
            try {
                ItemResult result = future.get();
                if (result.success()) {
                    images.addAll(result.images());
                } else {
                    errors.add(result.error());
                }
            } catch (Exception e) {
                errors.add(NormalizedError.normalize(e, requestTimeoutMs));
            }
        }

        Instant completedAt = Instant.now();
        Instant expiresAt = completedAt.plusMillis(ttlMs);
        if (!images.isEmpty()) {
            String warning = errors.isEmpty() ? null
                    : errors.size() + " 张图片生成失败: " + String.join("; ", errors);
            String resultJson = jsonObject(Map.of("images", images));
            repository.completeTask(taskId, resultJson, warning, completedAt.toString(), expiresAt.toString());
            taskMetrics.taskCompleted();
        } else {
            repository.failTask(taskId, "所有图片生成失败: " + String.join("; ", errors),
                    completedAt.toString(), expiresAt.toString());
            taskMetrics.taskFailed();
        }
        cleanupTaskRuntimeState(taskId);
        broadcaster.broadcastTask(taskId);
        broadcaster.broadcastQueueStatus();
    }

    public record ItemResult(boolean success, List<String> images, String error) {
    }

    /** Node generateSingleImage: generate → save/expand to disk → update item row. */
    private ItemResult generateSingleImage(String apiKey, TaskRequest request, String taskId, int index) {
        try {
            String image = imageGenService.generate(request.protocol(), apiKey, request);
            List<String> expanded = image.startsWith("MULTI_URL:")
                    ? java.util.Arrays.stream(image.substring(10).split("\\|\\|\\|")).map(u -> "URL:" + u).toList()
                    : List.of(image);
            List<String> diskRefs = new ArrayList<>();
            for (int subIdx = 0; subIdx < expanded.size(); subIdx++) {
                String img = expanded.get(subIdx);
                if (img.startsWith("URL:")) {
                    String remoteUrl = img.substring(4);
                    String httpUrl = imageStorageService.downloadUrlToDisk(taskId, index, subIdx, remoteUrl);
                    diskRefs.add("URL:" + httpUrl);
                } else {
                    byte[] buffer = java.util.Base64.getDecoder().decode(img);
                    String httpUrl = imageStorageService.saveImageToDisk(taskId, index, subIdx, buffer, "image/png");
                    diskRefs.add("URL:" + httpUrl);
                }
            }
            repository.updateItemImageData(taskId, index, TaskRepository.STATUS_COMPLETED,
                    jsonObject(diskRefs), Instant.now().toString());
            return new ItemResult(true, diskRefs, null);
        } catch (Exception e) {
            String message = NormalizedError.normalize(e, requestTimeoutMs);
            repository.updateItemError(taskId, index, TaskRepository.STATUS_FAILED, message, Instant.now().toString());
            return new ItemResult(false, List.of(), message);
        }
    }

    private String jsonObject(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    // ===== graceful shutdown (T1.9) =====

    /** Stops accepting new tasks and awaits in-flight tasks (bounded). */
    public void shutdown(long timeoutMs) {
        log.info("[shutdown] task queue shutting down, waiting up to {}ms for in-flight tasks", timeoutMs);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            synchronized (queue) {
                if (runningTasks.isEmpty() && queue.isEmpty()) {
                    log.info("[shutdown] all tasks finished");
                    break;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        executor.shutdown();
    }
}
