package com.nova.studio.task;

import com.nova.studio.auth.AuthFilter;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.ModelService;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Task orchestration (T1.1/T1.2/T1.9 + M2 T2.2/T2.4) — port of the Node backend's
 * {@code createTask} / {@code serializeTask} / {@code deleteTask} /
 * {@code cleanupExpiredTasks} ({@code backend/server.js}).
 *
 * <p>Create flow: require login (Q1, T2.2) → validate body → resolve model
 * config (server-side from {@code modelId}, T2.4) → accept/reject switch (503)
 * → dual-dimension rate limit (429) → queue capacity (503/429) → insert task +
 * items → register runtime state → enqueue. The WS task push / queue broadcast
 * hooks run through {@link TaskEventBroadcaster}.
 *
 * <p>M2 resolution: the task body's {@code model} field is the registry model
 * UUID; protocol/baseUrl/apiKey are resolved server-side per (user, modelId)
 * and the plaintext key never reaches the client (H2/Q2). Legacy apiKey/
 * baseUrl/protocol inputs are still honored when present (compatibility period).
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private static final Set<String> VALID_PROTOCOLS = Set.of("google", "openai", "grok");
    private static final String TASK_SOURCE = "nova";

    private final TaskRepository repository;
    private final TaskQueueService queueService;
    private final QueueStatsService queueStatsService;
    private final RateLimiterService rateLimiter;
    private final ShutdownFlag shutdownFlag;
    private final ImageStorageService imageStorageService;
    private final TaskEventBroadcaster broadcaster;
    private final TaskLookupService taskLookupService;
    private final ModelService modelService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;
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
                       ModelService modelService,
                       SettingsService settingsService,
                       ObjectMapper objectMapper,
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
        this.modelService = modelService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
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

        String apiKey = resolved.apiKey();
        String apiKeyHash = apiKey != null ? hashApiKey(apiKey) : "";
        enforceRateLimit(clientIp, apiKeyHash, config);
        enforceQueueCapacity(clientIp, apiKeyHash, config);

        String taskId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String nowIso = now.toString();
        JsonNode imagesNode = body.has("images") ? body.get("images") : null;
        List<TaskRequest.ImageReference> images = parseImages(imagesNode);

        // requestForDb: full params, images carry only mimeType (data stays in memory).
        Map<String, Object> requestForDb = new LinkedHashMap<>();
        requestForDb.put("mode", body.get("mode").asText());
        requestForDb.put("source", TASK_SOURCE);
        requestForDb.put("protocol", resolved.protocol());
        requestForDb.put("baseUrl", resolved.baseUrl());
        requestForDb.put("modelId", resolved.modelId());
        requestForDb.put("prompt", body.get("prompt").asText());
        putIfPresent(requestForDb, "outputSize", body, "outputSize");
        putIfPresent(requestForDb, "customSize", body, "customSize");
        putIfPresent(requestForDb, "aspectRatio", body, "aspectRatio");
        putIfPresent(requestForDb, "temperature", body, "temperature");
        requestForDb.put("model", body.get("model").asText());
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

        repository.insertTaskAndItems(taskId, userId, TaskRepository.STATUS_QUEUED,
                body.get("mode").asText(), requestJson, nowIso,
                body.get("parallelCount").asInt());

        queueService.registerRuntimeState(taskId, apiKey, images,
                new TaskQueueService.Source(clientIp, apiKeyHash));
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
     * M2 validation + server-side model resolution. The {@code model} field is
     * the registry UUID — resolved against the user's models (key decrypted
     * server-side, never sent to the client). Legacy apiKey/baseUrl/protocol
     * inputs are honored when present (compatibility period, H2/Q2).
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

        // Primary path (T2.4): resolve the registry UUID server-side.
        String modelKey = body.get("model").asText();
        ModelService.ResolvedModel resolved = modelService.resolve(userId, modelKey).orElse(null);
        if (resolved != null) {
            if (!resolved.type().equals("image")) {
                throw new IllegalArgumentException("模型不是图片模型");
            }
            if (resolved.apiKey() == null || resolved.apiKey().isBlank()) {
                throw new IllegalArgumentException("模型配置不完整，请先在设置中填写 API Key");
            }
            String normalized = ImageGenService.normalizeProtocolBaseUrl(resolved.protocol(), resolved.baseUrl());
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("缺少 API 基础地址");
            }
            return new ResolvedRequest(resolved.protocol(), normalized, resolved.apiKey(), resolved.modelId());
        }

        // Compatibility path: legacy apiKey/baseUrl/protocol from the request body.
        if (hasText(body, "apiKey") && hasText(body, "baseUrl")) {
            String protocol = body.get("protocol") != null && body.get("protocol").isTextual()
                    ? body.get("protocol").asText() : null;
            if (!VALID_PROTOCOLS.contains(protocol)) {
                throw new IllegalArgumentException("协议类型无效，必须为 google、openai 或 grok");
            }
            String normalized = ImageGenService.normalizeProtocolBaseUrl(protocol, body.get("baseUrl").asText());
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("缺少 API 基础地址");
            }
            String modelId = hasText(body, "modelId") ? body.get("modelId").asText() : body.get("model").asText();
            return new ResolvedRequest(protocol, normalized, body.get("apiKey").asText(), modelId);
        }

        throw new IllegalArgumentException("未找到模型配置");
    }

    private record ResolvedRequest(String protocol, String baseUrl, String apiKey, String modelId) {
    }

    private static boolean hasText(JsonNode body, String key) {
        JsonNode value = body.get(key);
        return value != null && value.isTextual() && !value.asText().trim().isEmpty();
    }

    /** Node enforceRateLimit: IP then apiKeyHash, both fixed-window. */
    private void enforceRateLimit(String clientIp, String apiKeyHash, QueueStatsService.LimitConfig config) {
        long ipRetry = rateLimiter.consume("ip:" + clientIp, config.maxRequestsPerIp(), config.rateLimitWindowMs());
        if (ipRetry > 0) {
            throw new HttpErrorException(429, "RATE_LIMITED", "请求太频繁，请稍后再试。",
                    Math.max(config.retryAfterSeconds(), (int) ipRetry));
        }
        if (!apiKeyHash.isEmpty()) {
            long keyRetry = rateLimiter.consume("api:" + apiKeyHash, config.maxRequestsPerApiKey(), config.rateLimitWindowMs());
            if (keyRetry > 0) {
                throw new HttpErrorException(429, "RATE_LIMITED", "请求太频繁，请稍后再试。",
                        Math.max(config.retryAfterSeconds(), (int) keyRetry));
            }
        }
    }

    /** Node enforceQueueCapacity: global queue size + per-source pending counts. */
    private void enforceQueueCapacity(String clientIp, String apiKeyHash, QueueStatsService.LimitConfig config) {
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
        if (queueService.getPendingCountByApiKeyHash(apiKeyHash) >= config.maxPendingTasksPerApiKey()) {
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
        // Isolation (T2.2): a user-owned task is only visible to its owner;
        // NULL-owner tasks (legacy/migrated) stay anonymously readable (Q1).
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
    public void cleanupExpiredTasks() {        List<String> expired = repository.findExpired(Instant.now());
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

    /** Node hashApiKey: sha256 hex, first 24 chars. */
    public static String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(String.valueOf(apiKey).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 24);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
