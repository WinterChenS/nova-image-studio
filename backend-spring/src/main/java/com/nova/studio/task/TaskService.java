package com.nova.studio.task;

import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.project.ProjectService;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Task orchestration (T1.1/T1.2/T1.9 + M2 T2.2/T2.4) — port of the Node backend's
 * {@code createTask} / {@code serializeTask} / {@code deleteTask} /
 * {@code cleanupExpiredTasks} ({@code backend/server.js}).
 *
 * <p>Create flow (WIN-28 T5): require login → validate body → resolve the
 * {@code model} field as a <b>catalog UUID</b> (global {@code ai_models},
 * replacing the per-user {@code models} resolution — Q1 直接移除) + usable
 * account pre-check (A5) → accept/reject switch (503) → dual-dimension rate
 * limit by IP + <b>userId</b> (T12/ADR-27, apiKeyHash → userId) → queue
 * capacity (503/429) → insert task + items → register runtime state (catalog
 * model id, no per-task apiKey — the account is selected at dispatch by
 * {@code TaskQueueService} via {@code AccountScheduler}) → enqueue.
 *
 * <p>The request JSON snapshot keeps {@code model} = catalog UUID and
 * {@code modelId} = upstream model name for historical detail display (B1);
 * the old per-user apiKey/baseUrl legacy inputs are removed.
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private static final String TASK_SOURCE = "nova";

    private final TaskRepository repository;
    private final TaskQueueService queueService;
    private final QueueStatsService queueStatsService;
    private final RateLimiterService rateLimiter;
    private final ShutdownFlag shutdownFlag;
    private final ImageStorageService imageStorageService;
    private final TaskEventBroadcaster broadcaster;
    private final TaskLookupService taskLookupService;
    private final CatalogModelService catalogModelService;
    private final AccountService accountService;
    private final SettingsService settingsService;
    private final ProjectService projectService;
    private final ObjectMapper objectMapper;
    private final TaskMetrics taskMetrics;
    private final long ackGraceMs;
    private final long ttlMs;

    public TaskService(TaskRepository repository,
                       TaskQueueService queueService,
                       QueueStatsService queueStatsService,
                       RateLimiterService rateLimiter,
                       ShutdownFlag shutdownFlag,
                       ImageStorageService imageStorageService,
                       TaskEventBroadcaster broadcaster,
                       TaskLookupService taskLookupService,
                       CatalogModelService catalogModelService,
                       AccountService accountService,
                       SettingsService settingsService,
                       ProjectService projectService,
                       ObjectMapper objectMapper,
                       TaskMetrics taskMetrics,
                       @Value("${nova.task.ack-grace-ms:120000}") long ackGraceMs,
                       @Value("${nova.task.ttl-ms:43200000}") long ttlMs) {
        this.repository = repository;
        this.queueService = queueService;
        this.queueStatsService = queueStatsService;
        this.rateLimiter = rateLimiter;
        this.shutdownFlag = shutdownFlag;
        this.imageStorageService = imageStorageService;
        this.broadcaster = broadcaster;
        this.taskLookupService = taskLookupService;
        this.catalogModelService = catalogModelService;
        this.accountService = accountService;
        this.settingsService = settingsService;
        this.projectService = projectService;
        this.objectMapper = objectMapper;
        this.taskMetrics = taskMetrics;
        this.ackGraceMs = ackGraceMs;
        this.ttlMs = ttlMs;
    }

    // ===== create (validate → limit → persist → enqueue) =====

    /** Node createTask(body, req) — returns the new task id. M2: login required. */
    public String createTask(JsonNode body, String clientIp, AuthUser authUser) {
        if (authUser == null) {
            throw new HttpErrorException(401, "UNAUTHORIZED", "请先登录");
        }
        UUID userId = authUser.id();
        ResolvedRequest resolved = validateAndResolve(body, userId);
        QueueStatsService.LimitConfig config = queueStatsService.getLimitConfig(userId);
        if (shutdownFlag.isShuttingDown() || queueStatsService.isRejectNewTasksEnabled()) {
            throw new HttpErrorException(503, "SERVER_NOT_ACCEPTING_TASKS",
                    "服务器正在升级维护，暂不接受新任务。未完成任务将继续完成。", config.retryAfterSeconds());
        }

        // T12 (ADR-27): 限流维度 apiKeyHash → userId（IP + 用户双维度）
        enforceRateLimit(clientIp, userId, config);
        enforceQueueCapacity(clientIp, userId, config);

        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String nowIso = now.toString();
        JsonNode imagesNode = body.has("images") ? body.get("images") : null;
        List<TaskRequest.ImageReference> images = parseImages(imagesNode);

        // WIN-22 (F-4): 任务携带 projectId —— 显式 id 校验属主（404），缺省兜底默认项目（ADR-17）
        String projectId = resolveProjectId(userId, body);

        // requestForDb: full params, images carry only mimeType (data stays in memory).
        Map<String, Object> requestForDb = new LinkedHashMap<>();
        requestForDb.put("mode", body.get("mode").asText());
        requestForDb.put("source", TASK_SOURCE);
        requestForDb.put("protocol", resolved.protocol());
        requestForDb.put("baseUrl", resolved.baseUrl());
        requestForDb.put("modelId", resolved.modelId());          // 上游模型名快照（历史详情展示，B1）
        requestForDb.put("prompt", body.get("prompt").asText());
        putIfPresent(requestForDb, "outputSize", body, "outputSize");
        putIfPresent(requestForDb, "customSize", body, "customSize");
        putIfPresent(requestForDb, "aspectRatio", body, "aspectRatio");
        putIfPresent(requestForDb, "temperature", body, "temperature");
        requestForDb.put("model", body.get("model").asText());    // 目录 UUID
        putIfPresent(requestForDb, "gptImageQuality", body, "gptImageQuality");
        putIfPresent(requestForDb, "gptImageStyle", body, "gptImageStyle");
        putIfPresent(requestForDb, "gptImageBackground", body, "gptImageBackground");
        requestForDb.put("parallelCount", body.get("parallelCount").asInt());
        List<Map<String, String>> mimeOnly = new ArrayList<>();
        for (TaskRequest.ImageReference img : images) {
            mimeOnly.add(Map.of("mimeType", String.valueOf(img.mimeType())));
        }
        requestForDb.put("images", mimeOnly);
        String requestJson = toJson(requestForDb);

        repository.insertTaskAndItems(taskId, userId, projectId, TaskRepository.STATUS_QUEUED,
                body.get("mode").asText(), requestJson, nowIso,
                body.get("parallelCount").asInt());
        taskMetrics.taskQueued();

        queueService.registerRuntimeState(taskId, resolved.catalogModelId().toString(), images,
                new TaskQueueService.Source(clientIp, userId.toString()));
        queueService.enqueue(taskId);
        return taskId;
    }

    private void putIfPresent(Map<String, Object> target, String key, JsonNode body, String bodyKey) {
        JsonNode value = body.get(bodyKey);
        if (value != null && !value.isNull()) {
            if (value.isNumber()) {
                target.put(key, value.asDouble());
            } else {
                target.put(key, value.asText());
            }
        }
    }

    /** WIN-22: project id resolution for task creation (explicit → ownership 404; absent → default project). */
    private String resolveProjectId(UUID userId, JsonNode body) {
        JsonNode projectNode = body.get("projectId");
        if (projectNode == null || projectNode.isNull() || (projectNode.isTextual() && projectNode.asText().isBlank())) {
            return projectService.defaultProjectId(userId);
        }
        if (!projectNode.isTextual()) {
            throw new IllegalArgumentException("projectId 格式无效");
        }
        String projectId = projectNode.asText();
        if (!projectService.owns(userId, projectId)) {
            throw new HttpErrorException(404, "NOT_FOUND", "项目不存在");
        }
        return projectId;
    }

    private List<TaskRequest.ImageReference> parseImages(JsonNode imagesNode) {
        List<TaskRequest.ImageReference> images = new ArrayList<>();
        if (imagesNode != null && imagesNode.isArray()) {
            for (JsonNode img : imagesNode) {
                String data = img.has("data") && img.get("data").isTextual() ? img.get("data").asText() : null;
                String mimeType = img.has("mimeType") && img.get("mimeType").isTextual() ? img.get("mimeType").asText() : null;
                images.add(new TaskRequest.ImageReference(data, mimeType));
            }
        }
        return images;
    }

    /**
     * T5 validation + catalog resolution (WIN-28): the {@code model} field is
     * a catalog UUID; the global model must be an enabled image model with at
     * least one usable account (A5, H1). The account itself is NOT reserved
     * here — only a pre-check (fast fail); dispatch selects it (E.2).
     */
    private ResolvedRequest validateAndResolve(JsonNode body, UUID userId) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String mode = body.get("mode") != null && body.get("mode").isTextual() ? body.get("mode").asText() : null;
        if (!"text-to-image".equals(mode) && !"image-to-image".equals(mode)) {
            throw new IllegalArgumentException("任务模式无效");
        }
        if (!hasText(body, "prompt")) {
            throw new IllegalArgumentException("提示词不能为空");
        }
        JsonNode parallel = body.get("parallelCount");
        if (parallel == null || !parallel.isIntegralNumber()
                || parallel.asInt() < 1 || parallel.asInt() > 4) {
            throw new IllegalArgumentException("并发数量无效");
        }
        if (!hasText(body, "model")) {
            throw new IllegalArgumentException("模型名称不能为空");
        }

        String modelKey = body.get("model").asText();
        UUID catalogId;
        try {
            catalogId = UUID.fromString(modelKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("未找到模型配置");
        }
        CatalogModelRepository.Row model = catalogModelService.resolve(catalogId)
                .orElseThrow(() -> new IllegalArgumentException("未找到模型配置"));
        if (!"image".equals(model.type())) {
            throw new IllegalArgumentException("模型不是图片模型");
        }
        if (model.enabled() == null || !model.enabled()) {
            throw new HttpErrorException(400, "MODEL_DISABLED", "模型已禁用，请联系管理员");
        }
        if (!accountService.hasCandidate(model)) {
            throw new HttpErrorException(400, "NO_AVAILABLE_ACCOUNT", "该模型暂无可用账号，请联系管理员");
        }
        String normalized = ImageGenService.normalizeProtocolBaseUrl(model.protocol(), model.baseUrl());
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("缺少 API 基础地址");
        }
        return new ResolvedRequest(model.protocol(), normalized, model.modelId(), catalogId);
    }

    private record ResolvedRequest(String protocol, String baseUrl, String modelId, UUID catalogModelId) {
    }

    private static boolean hasText(JsonNode body, String key) {
        JsonNode value = body.get(key);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty();
    }

    /** T12 (ADR-27): IP + userId 双维度 fixed-window 限流. */
    private void enforceRateLimit(String clientIp, UUID userId, QueueStatsService.LimitConfig config) {
        long ipRetry = rateLimiter.consume("ip:" + clientIp, config.maxRequestsPerIp(), config.rateLimitWindowMs());
        if (ipRetry > 0) {
            throw new HttpErrorException(429, "RATE_LIMITED", "请求太频繁，请稍后再试。",
                    Math.max(config.retryAfterSeconds(), (int) ipRetry));
        }
        long userRetry = rateLimiter.consume("user:" + userId, config.maxRequestsPerApiKey(), config.rateLimitWindowMs());
        if (userRetry > 0) {
            throw new HttpErrorException(429, "RATE_LIMITED", "请求太频繁，请稍后再试。",
                    Math.max(config.retryAfterSeconds(), (int) userRetry));
        }
    }

    /** Node enforceQueueCapacity: global queue size + per-source pending counts. */
    private void enforceQueueCapacity(String clientIp, UUID userId, QueueStatsService.LimitConfig config) {
        long pendingCount = queueStatsService.getQueueStatus().get("pendingCount") instanceof Number n
                ? n.longValue() : 0;
        if (pendingCount >= config.maxQueueSize()) {
            throw new HttpErrorException(503, "QUEUE_FULL", "当前排队任务较多，请稍后再试。",
                    config.retryAfterSeconds());
        }
        if (queueService.getPendingCountByIp(clientIp) >= config.maxPendingTasksPerIp()) {
            throw new HttpErrorException(429, "TOO_MANY_PENDING_TASKS",
                    "你已有较多任务正在排队或生成，请稍后再提交。", config.retryAfterSeconds());
        }
        if (queueService.getPendingCountByUser(userId.toString()) >= config.maxPendingTasksPerApiKey()) {
            throw new HttpErrorException(429, "TOO_MANY_PENDING_TASKS",
                    "你已有较多任务正在排队或生成，请稍后再提交。", config.retryAfterSeconds());
        }
    }

    // ===== read / ack / delete / cleanup =====

    /** Node serializeTask — returns the frontend task object (expired derived on read). */
    public Map<String, Object> getSerializedTask(String taskId, AuthUser authUser) {
        Map<String, Object> task = taskLookupService.loadTaskMessage(taskId);
        if (task == null) {
            return null;
        }
        // Isolation (T2.2) + D2 (ADR-30): a user-owned task is only visible to
        // its owner; NULL-owner tasks (legacy/migrated) are readable by any
        // logged-in user (登录收口后匿名不再可达).
        UUID owner = taskLookupService.findOwner(taskId);
        if (owner != null && (authUser == null || !owner.equals(authUser.id()))) {
            return null;
        }
        return task;
    }

    /** Node ack: renew expires_at by ACK_GRACE_MS (2min); always returns ok. */
    public void ackTask(String taskId) {
        if (repository.exists(taskId)) {
            repository.updateExpiresAt(taskId, Instant.now().plusMillis(ackGraceMs));
        }
    }

    /** Node deleteTask: remove image files, rows, runtime state, broadcast queue. */
    public void deleteTask(String taskId) {
        imageStorageService.deleteTaskImageFiles(taskId);
        repository.deleteTaskAndItems(taskId);
        queueService.cleanupTaskRuntimeState(taskId);
        broadcaster.broadcastQueueStatus();
    }

    /** Node cleanupExpiredTasks — TTL 12h, sweep every 5min. */
    public void cleanupExpiredTasks() {
        List<String> expired = repository.findExpired(Instant.now());
        int success = 0;
        int failed = 0;
        for (String id : expired) {
            broadcaster.broadcastTaskExpired(id);
            try {
                deleteTask(id);
                success++;
            } catch (Exception e) {
                failed++;
                log.warn("[cleanup] 过期任务删除失败: taskId={}", id, e);
            }
        }
        if (!expired.isEmpty()) {
            log.info("[cleanup] 本轮过期清理: 检查{}个任务, 成功{}个, 失败{}个", expired.size(), success, failed);
        }
    }

    /** Startup stale marking (T1.9) — Node initDatabase: interrupted tasks fail + images removed. */
    public List<String> markInterruptedTasksFailed() {
        repository.normalizeLegacyQueued();
        Instant now = Instant.now();
        List<String> interrupted = repository.markInterruptedTasksFailed(
                "服务器重启，任务已中断，请重新生成", now.toString(), now.plusMillis(ttlMs).toString());
        for (String id : interrupted) {
            imageStorageService.deleteTaskImageFiles(id);
        }
        return interrupted;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }
}
