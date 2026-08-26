package com.nova.studio.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Settings package service (T2.1) — per-user settings with the allowlist from
 * ARCH C.4 / PRD §3.3-A:
 * {@code registry.*} (default model assignment), {@code workbench.*} (form
 * defaults), {@code limit.*} (queue/rate-limit knobs), {@code gallery.*},
 * {@code agent.*} (Agent switches). UI prefs (theme/wide-mode) stay in the
 * browser and are rejected here.
 *
 * <p>Hot reads go through {@link SettingsCache} (Caffeine 1s TTL, ADR-6);
 * writes invalidate the user's entry immediately. The legacy localStorage
 * import (T2.6) imports <b>settings only</b> — the legacy model branch is
 * <b>下线</b> (WIN-33): the old {@code models} table is logically frozen
 * (PRD v1.1 §5 Q1/A2, ARCH C.4), so {@code nova-model-registry} is no longer
 * written; the response reports {@code modelsImport: "deprecated"}. Frontend
 * removal of the import wizard is M2 T19.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** Allowed key prefixes (ARCH C.4 settings namespace). */
    static final Set<String> ALLOWED_PREFIXES = Set.of("registry.", "workbench.", "limit.", "gallery.", "agent.", "canvas.");

    /** Node getLimitConfig defaults — used when a user has no limit.* rows. */
    public static final int DEFAULT_MAX_QUEUE_SIZE = 200;
    public static final int DEFAULT_RATE_LIMIT_WINDOW_MS = 60_000;
    public static final int DEFAULT_MAX_REQUESTS_PER_IP = 20;
    public static final int DEFAULT_MAX_REQUESTS_PER_API_KEY = 20;
    public static final int DEFAULT_MAX_PENDING_TASKS_PER_IP = 20;
    public static final int DEFAULT_MAX_PENDING_TASKS_PER_API_KEY = 10;
    public static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

    /**
     * WIN-39（WIN-40 T8，ARCH Part F.4）— 配额/保留默认值（settings limit.* 与 agent.* 可运行时覆盖）：
     * limit.agentConversationCap=100、limit.agentMessageCapPerConversation=500、
     * agent.contextCompressThreshold=60、agent.contextKeepRecent=20、limit.canvasProjectCap=100、
     * limit.historyCapReverse=500、limit.historyCapGif=100、limit.gifResultRetentionDays=180、
     * limit.archivedPurgeDays=180、limit.canvasRecycleDays=30、limit.assetRecycleDays=30。
     * 键名与默认值见 ARCH Part F.4；服务层校验点见 ConversationService/CanvasService（AC-11）。
     */
    public static final String KEY_AGENT_CONVERSATION_CAP = "limit.agentConversationCap";
    public static final String KEY_AGENT_MESSAGE_CAP = "limit.agentMessageCapPerConversation";
    public static final String KEY_CANVAS_PROJECT_CAP = "limit.canvasProjectCap";
    public static final String KEY_HISTORY_CAP_REVERSE = "limit.historyCapReverse";
    public static final String KEY_HISTORY_CAP_GIF = "limit.historyCapGif";
    public static final String KEY_GIF_RESULT_RETENTION_DAYS = "limit.gifResultRetentionDays";
    public static final String KEY_ARCHIVED_PURGE_DAYS = "limit.archivedPurgeDays";
    public static final String KEY_CANVAS_RECYCLE_DAYS = "limit.canvasRecycleDays";
    public static final String KEY_ASSET_RECYCLE_DAYS = "limit.assetRecycleDays";
    public static final String KEY_AGENT_CONTEXT_COMPRESS_THRESHOLD = "agent.contextCompressThreshold";
    public static final String KEY_AGENT_CONTEXT_KEEP_RECENT = "agent.contextKeepRecent";

    /** WIN-42 (T15, ADR-40) — 提示广场配置键（gallery.*，F.4）：手动刷新限频。 */
    public static final String KEY_GALLERY_SYNC_MANUAL_COOLDOWN_MINUTES = "gallery.syncManualCooldownMinutes";
    public static final int DEFAULT_GALLERY_SYNC_MANUAL_COOLDOWN_MINUTES = 10;

    /**
     * WIN-42 (T15/T18 核对修复②, ADR-40) — 广场每日同步 cron 配置键声明。
     * 实际生效路径：Spring 属性 {@code nova.gallery.sync-cron}（env {@code NOVA_GALLERY_SYNC_CRON}
     * 覆盖），见 {@code PromptGallerySyncScheduler}——Spring {@code @Scheduled} 的 cron
     * 表达式需启动期静态确定，且 settings 为 per-user 存储、广场同步为全局任务，无读取主体。
     * 本键为 ARCH F.4 文档约定<b>保留</b>，运行时暂不读取；P2 迁移分布式调度时启用。
     */
    public static final String KEY_GALLERY_SYNC_CRON = "gallery.syncCron";
    public static final String DEFAULT_GALLERY_SYNC_CRON = "0 0 3 * * *";

    /** WIN-42 (T16, A8) — 画布版本冲突校验开关（阶段3 打开：409 + 前端提示）。 */
    public static final String KEY_CANVAS_VERSION_CHECK = "canvas.versionCheckEnabled";
    public static final boolean DEFAULT_CANVAS_VERSION_CHECK = true;

    public static final int DEFAULT_AGENT_CONVERSATION_CAP = 100;
    public static final int DEFAULT_AGENT_MESSAGE_CAP = 500;
    public static final int DEFAULT_CANVAS_PROJECT_CAP = 100;
    public static final int DEFAULT_HISTORY_CAP_REVERSE = 500;
    public static final int DEFAULT_HISTORY_CAP_GIF = 100;
    public static final int DEFAULT_GIF_RESULT_RETENTION_DAYS = 180;
    public static final int DEFAULT_ARCHIVED_PURGE_DAYS = 180;
    public static final int DEFAULT_CANVAS_RECYCLE_DAYS = 30;
    public static final int DEFAULT_ASSET_RECYCLE_DAYS = 30;
    public static final int DEFAULT_AGENT_CONTEXT_COMPRESS_THRESHOLD = 60;
    public static final int DEFAULT_AGENT_CONTEXT_KEEP_RECENT = 20;

    private final SettingsRepository repository;
    private final SettingsCache cache;
    private final ObjectMapper objectMapper;

    public SettingsService(SettingsRepository repository, SettingsCache cache, ObjectMapper objectMapper) {
        this.repository = repository;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    // ===== read =====

    /** All settings for a user as parsed JSON values (flat dotted keys). */
    public Map<String, Object> getAll(UUID userId) {
        Map<String, String> raw = cache.get(userId);
        if (raw == null) {
            raw = repository.findAllByUser(userId);
            cache.put(userId, raw);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            result.put(entry.getKey(), parseJson(entry.getValue()));
        }
        return result;
    }

    /** Numeric setting for a user (limit.*), fallback when missing/invalid. */
    public int getInt(UUID userId, String key, int fallback) {
        Object value = getAll(userId).get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    public boolean getBoolean(UUID userId, String key, boolean fallback) {
        Object value = getAll(userId).get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String text) {
            return switch (text.trim().toLowerCase()) {
                case "1", "true", "yes", "on" -> true;
                case "0", "false", "no", "off" -> false;
                default -> fallback;
            };
        }
        return fallback;
    }

    // ===== write =====

    /** Whole-package upsert — validates allowlist, writes, invalidates cache. */
    public void putAll(UUID userId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        ((tools.jackson.databind.node.ObjectNode) body).properties().forEach(entry -> {
            String key = entry.getKey();
            if (!isAllowed(key)) {
                throw new IllegalArgumentException("不允许的设置项: " + key);
            }
            repository.upsert(userId, key, entry.getValue().toString(), "json");
        });
        cache.invalidate(userId);
    }

    public void delete(UUID userId, String key) {
        if (!isAllowed(key)) {
            throw new IllegalArgumentException("不允许的设置项: " + key);
        }
        repository.delete(userId, key);
        cache.invalidate(userId);
    }

    // ===== legacy localStorage import (T2.6) =====

    /**
     * Import a legacy localStorage export (the object the old frontend stored,
     * e.g. the {@code localStorage.json} inside an old backup ZIP or the direct
     * export of the migration wizard): form-default keys → {@code workbench.*},
     * agent keys → {@code agent.*}.
     *
     * <p>WIN-33: the legacy model branch ({@code nova-model-registry} → old
     * {@code models} table) is <b>下线</b> — the table is logically frozen
     * (PRD v1.1 §5 Q1/A2, ARCH C.4 “业务代码不再读写 models”), so imported
     * models are NOT written anymore; the response keeps the legacy shape
     * ({@code modelsCreated: 0}) and reports {@code modelsImport: "deprecated"}
     * for the frontend/M2 T19 to surface. Settings import is unchanged.
     */
    public Map<String, Object> importLegacy(UUID userId, JsonNode legacy) {
        if (legacy == null || !legacy.isObject()) {
            throw new IllegalArgumentException("导入数据格式无效");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        int settingsWritten = 0;
        settingsWritten += importWorkbench(userId, legacy, "nova-t2i-settings", "workbench.t2i");
        settingsWritten += importWorkbench(userId, legacy, "nova-i2i-settings", "workbench.i2i");
        settingsWritten += importWorkbench(userId, legacy, "nova-reverse-prompt-settings", "workbench.reverse");
        settingsWritten += importWorkbench(userId, legacy, "nova-gif-settings", "workbench.gif");
        settingsWritten += importWorkbench(userId, legacy, "nova-agent-params", "workbench.agent");
        settingsWritten += importBoolean(userId, legacy, "nova-agent-web-search", "agent.webSearch");
        settingsWritten += importBoolean(userId, legacy, "nova-agent-intent-recognition", "agent.intentRecognition");
        summary.put("settingsWritten", settingsWritten);
        summary.put("modelsCreated", 0);
        summary.put("modelsImport", "deprecated");
        return summary;
    }

    private int importWorkbench(UUID userId, JsonNode legacy, String legacyKey, String settingsKey) {
        JsonNode value = legacy.get(legacyKey);
        if (value == null || value.isNull()) {
            return 0;
        }
        repository.upsert(userId, settingsKey, value.toString(), "json");
        cache.invalidate(userId);
        return 1;
    }

    private int importBoolean(UUID userId, JsonNode legacy, String legacyKey, String settingsKey) {
        JsonNode value = legacy.get(legacyKey);
        if (value == null || value.isNull()) {
            return 0;
        }
        repository.upsert(userId, settingsKey, String.valueOf(value.asBoolean()), "json");
        cache.invalidate(userId);
        return 1;
    }

    // ===== helpers =====

    static boolean isAllowed(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return ALLOWED_PREFIXES.stream().anyMatch(key::startsWith);
    }

    private Object parseJson(String raw) {
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return raw;
        }
    }
}
