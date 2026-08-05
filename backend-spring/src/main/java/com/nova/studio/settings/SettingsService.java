package com.nova.studio.settings;

import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.ModelService.ResolvedModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
 * import (T2.6) maps old registry ids onto fresh server UUIDs and rewrites
 * {@code registry.defaults} accordingly.
 */
@Service
public class SettingsService {

    private static final Logger log = LoggerFactory.getLogger(SettingsService.class);

    /** Allowed key prefixes (ARCH C.4 settings namespace). */
    static final Set<String> ALLOWED_PREFIXES = Set.of("registry.", "workbench.", "limit.", "gallery.", "agent.");

    /** Node getLimitConfig defaults — used when a user has no limit.* rows. */
    public static final int DEFAULT_MAX_QUEUE_SIZE = 200;
    public static final int DEFAULT_RATE_LIMIT_WINDOW_MS = 60_000;
    public static final int DEFAULT_MAX_REQUESTS_PER_IP = 20;
    public static final int DEFAULT_MAX_REQUESTS_PER_API_KEY = 20;
    public static final int DEFAULT_MAX_PENDING_TASKS_PER_IP = 20;
    public static final int DEFAULT_MAX_PENDING_TASKS_PER_API_KEY = 10;
    public static final int DEFAULT_RETRY_AFTER_SECONDS = 30;

    private final SettingsRepository repository;
    private final SettingsCache cache;
    private final ModelRepository modelRepository;
    private final ModelService modelService;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;

    public SettingsService(SettingsRepository repository, SettingsCache cache,
                           ModelRepository modelRepository, ModelService modelService,
                           CryptoService crypto, ObjectMapper objectMapper) {
        this.repository = repository;
        this.cache = cache;
        this.modelRepository = modelRepository;
        this.modelService = modelService;
        this.crypto = crypto;
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
     * export of the migration wizard): {@code nova-model-registry} → models +
     * {@code registry.defaults} (ids remapped to server UUIDs), form-default
     * keys → {@code workbench.*}, agent keys → {@code agent.*}.
     */
    public Map<String, Object> importLegacy(UUID userId, JsonNode legacy) {
        if (legacy == null || !legacy.isObject()) {
            throw new IllegalArgumentException("导入数据格式无效");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        int modelsCreated = importLegacyRegistry(userId, legacy.get("nova-model-registry"));
        summary.put("modelsCreated", modelsCreated);
        int settingsWritten = 0;
        settingsWritten += importWorkbench(userId, legacy, "nova-t2i-settings", "workbench.t2i");
        settingsWritten += importWorkbench(userId, legacy, "nova-i2i-settings", "workbench.i2i");
        settingsWritten += importWorkbench(userId, legacy, "nova-reverse-prompt-settings", "workbench.reverse");
        settingsWritten += importWorkbench(userId, legacy, "nova-gif-settings", "workbench.gif");
        settingsWritten += importWorkbench(userId, legacy, "nova-agent-params", "workbench.agent");
        settingsWritten += importBoolean(userId, legacy, "nova-agent-web-search", "agent.webSearch");
        settingsWritten += importBoolean(userId, legacy, "nova-agent-intent-recognition", "agent.intentRecognition");
        summary.put("settingsWritten", settingsWritten);
        return summary;
    }

    private int importLegacyRegistry(UUID userId, JsonNode registry) {
        if (registry == null || !registry.isObject()) {
            return 0;
        }
        int created = 0;
        Map<String, String> idMapping = new LinkedHashMap<>();

        JsonNode imageModels = registry.get("imageModels");
        if (imageModels != null && imageModels.isArray()) {
            for (JsonNode model : imageModels) {
                String oldId = text(model, "id");
                String newId = insertLegacyModel(userId, "image", model);
                if (newId != null) {
                    idMapping.put(oldId, newId);
                    created++;
                }
            }
        }
        JsonNode textModels = registry.get("textModels");
        if (textModels != null && textModels.isArray()) {
            for (JsonNode model : textModels) {
                String oldId = text(model, "id");
                String newId = insertLegacyModel(userId, "text", model);
                if (newId != null) {
                    idMapping.put(oldId, newId);
                    created++;
                }
            }
        }

        JsonNode defaults = registry.get("defaults");
        if (defaults != null && defaults.isObject()) {
            ObjectNode mappedDefaults = objectMapper.createObjectNode();
            ((tools.jackson.databind.node.ObjectNode) defaults).properties().forEach(entry -> {
                // defaults shape: { slotName: legacyModelId } — the legacy model
                // id is the VALUE; remap it onto the fresh server UUID.
                String mapped = entry.getValue().isTextual()
                        ? idMapping.get(entry.getValue().asText()) : null;
                mappedDefaults.put(entry.getKey(), mapped != null ? mapped : "");
            });
            repository.upsert(userId, "registry.defaults", mappedDefaults.toString(), "json");
        }
        cache.invalidate(userId);
        return created;
    }

    private String insertLegacyModel(UUID userId, String type, JsonNode model) {
        String protocol = text(model, "protocol");
        String name = text(model, "name");
        String modelId = text(model, "modelId");
        String baseUrl = text(model, "baseUrl");
        if (protocol == null || name == null || modelId == null || baseUrl == null) {
            return null;
        }
        Set<String> allowed = "image".equals(type) ? ModelService.IMAGE_PROTOCOLS : ModelService.TEXT_PROTOCOLS;
        if (!allowed.contains(protocol)) {
            return null;
        }
        String apiKey = text(model, "apiKey");
        String keyEnc = apiKey != null && !apiKey.isBlank() && !apiKey.contains("***") ? crypto.encrypt(apiKey.trim()) : null;

        ObjectNode caps = objectMapper.createObjectNode();
        String builtinPreset = null;
        if ("image".equals(type)) {
            builtinPreset = text(model, "builtinPreset");
            caps.put("max_ref_images", number(model, "maxRefImages", 0));
            caps.put("max_output_size", text(model, "maxOutputSize", "1K"));
            caps.put("supports_advanced_params", booleanValue(model, "supportsAdvancedParams", false));
        } else {
            String note = text(model, "note");
            if (note != null) {
                caps.put("note", note);
            }
        }
        return modelRepository.insert(userId, type, protocol, name.trim(), modelId.trim(), baseUrl.trim(),
                keyEnc, caps.toString(), builtinPreset).toString();
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

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static double number(JsonNode node, String key, double fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    private static boolean booleanValue(JsonNode node, String key, boolean fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }
}
