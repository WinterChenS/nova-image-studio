package com.nova.studio.task;

import com.nova.studio.accountpool.AccountHealthService;
import com.nova.studio.accountpool.AccountRepository;
import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.audit.UsageCollector;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.HttpErrorException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Task queue with slot concurrency (T1.2) — port of the Node backend's
 * {@code drainQueue} / {@code runTask} / {@code generateSingleImage}
 * ({@code backend/server.js}), WIN-28 T5 pool dispatch:
 * <ul>
 *   <li>concurrency = image <b>slots</b> ({@code parallelCount} per task), cap 50;</li>
 *   <li>state machine: '排队中' → processing → completed|failed;</li>
 *   <li>runtime state (catalog model id / ref images / per-user source counters)
 *       held in memory — the account is <b>selected at dispatch</b> by
 *       {@link AccountScheduler} (least in-flight + round-robin + cooldown,
 *       ADR-23), in-flight tracked per account, and retriable failures switch
 *       accounts ≤2 retries (R3: 401/4xx never retried; A3/A4/A21);</li>
 *   <li>health outcomes recorded via {@link AccountHealthService} (broken
 *       threshold → manual recovery).</li>
 * </ul>
 */
@Service
public class TaskQueueService {

    private static final Logger log = LoggerFactory.getLogger(TaskQueueService.class);

    /** R3: 换账号重试 ≤2 次（最多 3 次尝试）。 */
    static final int MAX_ATTEMPTS = 3;

    private final TaskRepository repository;
    private final ImageGenService imageGenService;
    private final ImageStorageService imageStorageService;
    private final TaskEventBroadcaster broadcaster;
    private final ObjectMapper objectMapper;
    private final QueueStatsService queueStatsService;
    private final TaskMetrics taskMetrics;
    private final AccountScheduler accountScheduler;
    private final AccountHealthService accountHealthService;
    private final AccountService accountService;
    private final CatalogModelService catalogModelService;
    private final UsageCollector usageCollector;
    private final long ttlMs;
    private final long requestTimeoutMs;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
    private final Map<String, Future<?>> runningTasks = new ConcurrentHashMap<>();
    private int activeSlots = 0;

    // Runtime state mirrors (Node: apiKeys / taskRefImages / taskSources / pending counters)
    private final Map<String, String> catalogModelIds = new ConcurrentHashMap<>();
    private final Map<String, List<TaskRequest.ImageReference>> taskRefImages = new ConcurrentHashMap<>();
    private final Map<String, Source> taskSources = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingCountByIp = new ConcurrentHashMap<>();
    private final Map<String, Integer> pendingCountByUserId = new ConcurrentHashMap<>();

    /** T12 (ADR-27): per-source = IP + userId (apiKeyHash 维度下线). */
    public record Source(String ip, String userId) {
    }

    public TaskQueueService(TaskRepository repository,
                            ImageGenService imageGenService,
                            ImageStorageService imageStorageService,
                            TaskEventBroadcaster broadcaster,
                            ObjectMapper objectMapper,
                            QueueStatsService queueStatsService,
                            TaskMetrics taskMetrics,
                            AccountScheduler accountScheduler,
                            AccountHealthService accountHealthService,
                            AccountService accountService,
                            CatalogModelService catalogModelService,
                            UsageCollector usageCollector,
                            @Value("${nova.task.ttl-ms:43200000}") long ttlMs,
                            @Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs) {
        this.repository = repository;
        this.imageGenService = imageGenService;
        this.imageStorageService = imageStorageService;
        this.broadcaster = broadcaster;
        this.objectMapper = objectMapper;
        this.queueStatsService = queueStatsService;
        this.taskMetrics = taskMetrics;
        this.accountScheduler = accountScheduler;
        this.accountHealthService = accountHealthService;
        this.accountService = accountService;
        this.catalogModelService = catalogModelService;
        this.usageCollector = usageCollector;
        this.ttlMs = ttlMs;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    // ===== runtime state =====

    public void registerRuntimeState(String taskId, String catalogModelId, List<TaskRequest.ImageReference> images,
                                     Source source) {
        if (catalogModelId != null) {
            catalogModelIds.put(taskId, catalogModelId);
        }
        if (images != null) {
            taskRefImages.put(taskId, images);
        }
        if (source != null) {
            taskSources.put(taskId, source);
            if (source.ip() != null) {
                pendingCountByIp.merge(source.ip(), 1, Integer::sum);
            }
            if (source.userId() != null) {
                pendingCountByUserId.merge(source.userId(), 1, Integer::sum);
            }
        }
    }

    public int getPendingCountByIp(String ip) {
        return ip == null ? 0 : pendingCountByIp.getOrDefault(ip, 0);
    }

    public int getPendingCountByUser(String userId) {
        return userId == null ? 0 : pendingCountByUserId.getOrDefault(userId, 0);
    }

    public void cleanupTaskRuntimeState(String taskId) {
        Source source = taskSources.remove(taskId);
        if (source != null) {
            decrement(pendingCountByIp, source.ip());
            decrement(pendingCountByUserId, source.userId());
        }
        catalogModelIds.remove(taskId);
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

    private Optional<TaskRequest> parseRequest(String requestJson) {
        try {
            return Optional.of(TaskRequest.fromStored(objectMapper.readTree(requestJson), null));
        } catch (Exception e) {
            return Optional.empty();
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
        Instant startedAt = Instant.now();
        String catalogModelId = catalogModelIds.get(taskId);
        if (rowOpt.isEmpty() || catalogModelId == null
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

        // T5: 派发时解析目录模型（runtime state 的 catalog UUID）→ 调度选号
        CatalogModelRepository.Row catalogModel = resolveCatalogModel(catalogModelId);
        if (catalogModel == null) {
            repository.failTask(taskId, "目录模型不存在或已删除，请重新选择模型",
                    Instant.now().toString(), Instant.now().plusMillis(ttlMs).toString());
            taskMetrics.taskFailed();
            cleanupTaskRuntimeState(taskId);
            broadcaster.broadcastTask(taskId);
            broadcaster.broadcastQueueStatus();
            return;
        }

        repository.updateStatus(taskId, TaskRepository.STATUS_PROCESSING);
        taskMetrics.taskProcessing();
        broadcaster.broadcastTask(taskId);
        broadcaster.broadcastQueueStatus();

        String processingAt = Instant.now().toString();
        for (int index = 0; index < request.parallelCount(); index++) {
            repository.updateItemProcessing(taskId, index, processingAt);
        }

        // Parallel item generation (Node Promise.allSettled), each item selects
        // its own account at dispatch (E.2) with retriable account switching.
        List<Future<ItemResult>> futures = new ArrayList<>();
        final TaskRequest req = request;
        for (int index = 0; index < request.parallelCount(); index++) {
            final int idx = index;
            futures.add(executor.submit(() -> generateSingleImageWithRetry(catalogModel, req, taskId, idx)));
        }
        List<String> images = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int successfulImages = 0;
        boolean anyRetried = false;
        UUID dominantAccount = null;
        for (Future<ItemResult> future : futures) {
            try {
                ItemResult result = future.get();
                if (result.success()) {
                    images.addAll(result.images());
                    successfulImages += result.images().size();
                    dominantAccount = result.accountId();   // 最后成功项的账号为最终 account（A3）
                    anyRetried |= result.retried();
                } else {
                    errors.add(result.error());
                    if (dominantAccount == null) {
                        dominantAccount = result.accountId();
                    }
                }
            } catch (Exception e) {
                errors.add(NormalizedError.normalize(e, requestTimeoutMs));
            }
        }

        Instant completedAt = Instant.now();
        Instant expiresAt = completedAt.plusMillis(ttlMs);
        boolean allFailed = images.isEmpty();
        if (!allFailed) {
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
        // T7: worker 完成后写 usage（task 级一条，幂等 UNIQUE(ref_type, ref_id)）
        usageCollector.recordTaskUsage(new UsageCollector.TaskUsage(
                taskId, parseUserId(rowOpt.get().userId()), catalogModel.id(), catalogModel.protocol(),
                request.parallelCount(), successfulImages, anyRetried, allFailed, dominantAccount,
                java.time.Duration.between(startedAt, completedAt).toMillis()));
        cleanupTaskRuntimeState(taskId);
        broadcaster.broadcastTask(taskId);
        broadcaster.broadcastQueueStatus();
    }

    private UUID parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private CatalogModelRepository.Row resolveCatalogModel(String catalogModelId) {
        try {
            return catalogModelService.resolve(UUID.fromString(catalogModelId)).orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record ItemResult(boolean success, List<String> images, String error, UUID accountId, boolean retried) {
    }

    /**
     * Node generateSingleImage on the account pool (T5): select account →
     * generate → save to disk → update item row. Retriable upstream failures
     * (429/5xx/timeout/conn, R3) switch accounts up to {@value MAX_ATTEMPTS}
     * attempts; 401/other 4xx fail the item immediately. Storage failures are
     * not retried (regeneration would double-bill).
     */
    private ItemResult generateSingleImageWithRetry(CatalogModelRepository.Row catalogModel,
                                                    TaskRequest request, String taskId, int index) {
        Set<UUID> tried = new HashSet<>();
        String lastError = null;
        int attempts = 0;
        while (true) {
            AccountScheduler.SelectedAccount selected;
            try {
                selected = accountScheduler.select(catalogModel, tried);
            } catch (HttpErrorException e) {
                lastError = e.getMessage();
                break;   // 无更多候选账号
            }
            tried.add(selected.accountId());
            attempts++;
            try {
                TaskRequest effective = effectiveRequest(request, catalogModel, selected);
                String image = imageGenService.generate(selected.protocol(), selected.apiKey(), effective);
                recordHealthSuccess(selected);
                // 存储落盘 — 非重试域（避免重复计费）
                List<String> diskRefs = saveImages(image, taskId, index);
                repository.updateItemImageData(taskId, index, TaskRepository.STATUS_COMPLETED,
                        jsonObject(diskRefs), Instant.now().toString());
                return new ItemResult(true, diskRefs, null, selected.accountId(), attempts > 1);
            } catch (Exception e) {
                AccountHealthService.ErrorKind kind = accountHealthService.classify(e);
                recordHealthFailure(selected, kind, e.getMessage());
                lastError = NormalizedError.normalize(e, requestTimeoutMs);
                if (kind.retriable() && attempts < MAX_ATTEMPTS) {
                    continue;   // 换账号重试（finally 已释放旧账号在飞）
                }
                break;
            } finally {
                accountScheduler.release(selected.accountId());   // E.4: 在飞 --（重试时旧号 -1，新号 select 时 +1）
            }
        }
        repository.updateItemError(taskId, index, TaskRepository.STATUS_FAILED, lastError, Instant.now().toString());
        return new ItemResult(false, List.of(), lastError, null, attempts > 1);
    }

    /** 目录模型 + 选中账号合成实际生成参数（上游 modelId 用目录模型名，协议/地址/Key 用账号）。 */
    private TaskRequest effectiveRequest(TaskRequest request, CatalogModelRepository.Row catalogModel,
                                         AccountScheduler.SelectedAccount selected) {
        return new TaskRequest(request.mode(), selected.protocol(), selected.baseUrl(), request.prompt(),
                request.outputSize(), request.customSize(), request.aspectRatio(), request.temperature(),
                catalogModel.modelId(), request.gptImageQuality(), request.gptImageStyle(), request.gptImageBackground(),
                request.parallelCount(), request.images());
    }

    private List<String> saveImages(String image, String taskId, int index) {
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
        return diskRefs;
    }

    private void recordHealthSuccess(AccountScheduler.SelectedAccount selected) {
        AccountRepository.Row row = accountService.findById(selected.accountId()).orElse(null);
        if (row != null) {
            accountHealthService.recordSuccess(row);
        }
    }

    private void recordHealthFailure(AccountScheduler.SelectedAccount selected, AccountHealthService.ErrorKind kind, String message) {
        AccountRepository.Row row = accountService.findById(selected.accountId()).orElse(null);
        if (row != null) {
            accountHealthService.recordFailure(row, kind, message);
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
