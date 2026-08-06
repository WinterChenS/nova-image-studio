package com.nova.studio.accountpool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nova.studio.infra.HttpErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * T2 (WIN-28) — global model catalog service ({@code ai_models}). Admin CRUD
 * replaces the per-user {@code models} semantics (old table logically frozen,
 * P2 physical cleanup); the public read path (also served via
 * {@code GET /api/nova/models}) returns only enabled models with an
 * {@code available} flag (at least one schedulable account, A5).
 *
 * <p>Delete = physical row delete + pricing cascade (FK ON DELETE CASCADE) +
 * account model_scope cleanup (ADR-28, R1) — usage_records keep history via
 * ON DELETE SET NULL.
 */
@Service
public class CatalogModelService {

    private static final Logger log = LoggerFactory.getLogger(CatalogModelService.class);

    static final Set<String> IMAGE_PROTOCOLS = Set.of("google", "openai", "grok");
    static final Set<String> TEXT_PROTOCOLS = Set.of(
            "google-gemini", "anthropic-messages", "openai-chat-completions", "openai-responses");

    private final CatalogModelRepository repository;
    private final AccountService accountService;
    private final ObjectMapper objectMapper;
    private final Cache<UUID, CatalogModelRepository.Row> byIdCache;
    private final Cache<String, List<CatalogModelRepository.Row>> allCache;

    public CatalogModelService(CatalogModelRepository repository,
                               AccountService accountService,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.accountService = accountService;
        this.objectMapper = objectMapper;
        this.byIdCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1)).maximumSize(10_000).build();
        this.allCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1)).maximumSize(10).build();
    }

    // ===== admin CRUD =====

    public List<Map<String, Object>> listAdmin() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CatalogModelRepository.Row row : allModels()) {
            result.add(toAdminDto(row));
        }
        return result;
    }

    public Map<String, Object> create(UUID adminId, JsonNode body) {
        Validated valid = validate(body);
        if (repository.existsProtocolModelId(valid.protocol(), valid.modelId())) {
            throw new HttpErrorException(409, "MODEL_TAKEN", "该协议下同名模型已存在");
        }
        UUID id = repository.insert(valid.type(), valid.protocol(), valid.name(), valid.modelId(),
                valid.baseUrl(), capabilitiesJson(valid, body), valid.builtinPresetId(), true, adminId);
        invalidate();
        return repository.findById(id).map(this::toAdminDto)
                .orElseThrow(() -> new IllegalStateException("目录模型创建后读取失败"));
    }

    public Map<String, Object> update(UUID adminId, UUID id, JsonNode body) {
        CatalogModelRepository.Row existing = repository.findById(id)
                .orElseThrow(() -> new HttpErrorException(404, "MODEL_NOT_FOUND", "模型不存在"));
        Validated valid = validate(body);
        if (!existing.protocol().equals(valid.protocol()) || !existing.modelId().equals(valid.modelId())) {
            if (repository.existsProtocolModelId(valid.protocol(), valid.modelId())) {
                throw new HttpErrorException(409, "MODEL_TAKEN", "该协议下同名模型已存在");
            }
        }
        boolean enabled = body.hasNonNull("enabled") ? body.get("enabled").asBoolean(existing.enabled() != null && existing.enabled())
                : existing.enabled() != null && existing.enabled();
        repository.update(id, valid.type(), valid.protocol(), valid.name(), valid.modelId(),
                valid.baseUrl(), capabilitiesJson(valid, body), valid.builtinPresetId(), enabled);
        invalidate();
        return repository.findById(id).map(this::toAdminDto)
                .orElseThrow(() -> new IllegalStateException("目录模型更新后读取失败"));
    }

    public void delete(UUID adminId, UUID id) {
        if (repository.deleteById(id) == 0) {
            throw new HttpErrorException(404, "MODEL_NOT_FOUND", "模型不存在");
        }
        accountService.removeModelFromScopes(id);   // ADR-28: scope 联动清理
        invalidate();
    }

    // ===== public catalog read (A2/A5) =====

    /** Enabled catalog models with the {@code available} flag (normal users). */
    public List<Map<String, Object>> listPublicCatalog() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (CatalogModelRepository.Row row : enabledModels()) {
            // 防御性过滤：禁用模型绝不出现（A5）
            if (row.enabled() == null || !row.enabled()) {
                continue;
            }
            result.add(toPublicDto(row));
        }
        return result;
    }

    /** Resolve by catalog UUID (task/proxy dispatch). */
    public Optional<CatalogModelRepository.Row> resolve(UUID id) {
        CatalogModelRepository.Row row = byIdCache.get(id, k -> repository.findById(k).orElse(null));
        return Optional.ofNullable(row);
    }

    // ===== helpers =====

    private List<CatalogModelRepository.Row> allModels() {
        return allCache.get("all", k -> repository.findAll());
    }

    private List<CatalogModelRepository.Row> enabledModels() {
        return allCache.get("enabled", k -> repository.findAllEnabled());
    }

    private void invalidate() {
        allCache.invalidateAll();
        byIdCache.invalidateAll();
    }

    private record Validated(String type, String protocol, String name, String modelId,
                             String baseUrl, String builtinPresetId) {
    }

    private Validated validate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String type = text(body, "type");
        if (type == null || (!"image".equals(type) && !"text".equals(type))) {
            throw new IllegalArgumentException("模型类型无效，必须为 image 或 text");
        }
        String protocol = text(body, "protocol");
        Set<String> allowed = "image".equals(type) ? IMAGE_PROTOCOLS : TEXT_PROTOCOLS;
        if (protocol == null || !allowed.contains(protocol)) {
            throw new IllegalArgumentException("协议类型无效");
        }
        String name = text(body, "name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        String modelId = text(body, "modelId");
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        String baseUrl = text(body, "baseUrl");
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("API 基础地址不能为空");
        }
        return new Validated(type, protocol, name.trim(), modelId.trim(), baseUrl.trim(), text(body, "builtinPreset"));
    }

    private String capabilitiesJson(Validated valid, JsonNode body) {
        ObjectNode caps = objectMapper.createObjectNode();
        if ("image".equals(valid.type())) {
            caps.put("max_ref_images", number(body, "maxRefImages", 0));
            caps.put("max_output_size", text(body, "maxOutputSize", "1K"));
            caps.put("supports_advanced_params", bool(body, "supportsAdvancedParams", false));
        } else {
            String note = text(body, "note");
            if (note != null) {
                caps.put("note", note);
            }
        }
        return caps.toString();
    }

    /** Admin DTO — full view (no secrets on this table). */
    private Map<String, Object> toAdminDto(CatalogModelRepository.Row row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", row.id().toString());
        dto.put("type", row.type());
        dto.put("protocol", row.protocol());
        dto.put("name", row.name());
        dto.put("modelId", row.modelId());
        dto.put("baseUrl", row.baseUrl());
        dto.put("enabled", row.enabled());
        dto.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        dto.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        JsonNode caps = parseCaps(row.capabilitiesJson());
        if ("image".equals(row.type())) {
            dto.put("builtinPreset", row.builtinPresetId() != null ? row.builtinPresetId() : text(caps, "builtin_preset", ""));
            dto.put("maxRefImages", caps.path("max_ref_images").asInt(0));
            dto.put("maxOutputSize", caps.path("max_output_size").asText("1K"));
            dto.put("supportsAdvancedParams", caps.path("supports_advanced_params").asBoolean(false));
        } else {
            dto.put("note", caps.path("note").asText(""));
        }
        return dto;
    }

    /**
     * Public DTO — keeps the legacy frontend field names (builtinPreset /
     * maxRefImages / maxOutputSize / supportsAdvancedParams) so the workbench
     * model dropdown keeps rendering until M2 ships the new UI; adds
     * {@code available} (A5: enabled=false 或 无可用账号 → 不可选).
     */
    private Map<String, Object> toPublicDto(CatalogModelRepository.Row row) {
        Map<String, Object> dto = new LinkedHashMap<>(toAdminDto(row));
        dto.put("available", accountService.hasCandidate(row));
        dto.remove("createdAt");
        dto.remove("updatedAt");
        return dto;
    }

    private JsonNode parseCaps(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[catalog] capabilities 解析失败: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String key) {
        return text(node, key, null);
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static double number(JsonNode node, String key, double fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isNumber() ? value.asDouble() : fallback;
    }

    private static boolean bool(JsonNode node, String key, boolean fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }
}
