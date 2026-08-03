package com.nova.studio.settings;

import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.infra.RuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Model registry CRUD (T2.1) — per-user models with AES-GCM-encrypted API keys
 * (ADR-8) and masked responses. The write path accepts either a new plaintext
 * key or a masked/empty one (kept from the stored ciphertext), so the
 * frontend's save-without-retyping-key flow never wipes credentials.
 *
 * <p>Protocol whitelists follow the frontend contracts: image →
 * google/openai/grok; text → google-gemini/anthropic-messages/
 * openai-chat-completions/openai-responses.
 */
@Service
public class ModelService {

    private static final Logger log = LoggerFactory.getLogger(ModelService.class);

    static final Set<String> IMAGE_PROTOCOLS = Set.of("google", "openai", "grok");
    static final Set<String> TEXT_PROTOCOLS = Set.of(
            "google-gemini", "anthropic-messages", "openai-chat-completions", "openai-responses");

    private final ModelRepository repository;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private final RuntimeEnv runtimeEnv;

    public ModelService(ModelRepository repository, CryptoService crypto, ObjectMapper objectMapper, RuntimeEnv runtimeEnv) {
        this.repository = repository;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.runtimeEnv = runtimeEnv;
    }

    // ===== public API (masked) =====

    /** All models for a user, keys masked. */
    public List<Map<String, Object>> list(UUID userId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelRepository.ModelRow row : repository.findByUserId(userId)) {
            result.add(toDto(row));
        }
        return result;
    }

    public Map<String, Object> create(UUID userId, JsonNode body) {
        ValidatedModel validated = validate(userId, body, null);
        enforceNameUnique(userId, validated.type(), validated.name(), null);
        String keyEnc = encryptKey(body);
        UUID id = repository.insert(userId, validated.type(), validated.protocol(), validated.name(),
                validated.modelId(), validated.baseUrl(), keyEnc, capabilitiesJson(validated, body), validated.builtinPresetId());
        return repository.findByIdAndUser(id, userId).map(this::toDto)
                .orElseThrow(() -> new IllegalStateException("模型创建后读取失败"));
    }

    public Map<String, Object> update(UUID userId, UUID modelId, JsonNode body) {
        ModelRepository.ModelRow existing = repository.findByIdAndUser(modelId, userId)
                .orElseThrow(() -> new HttpErrorException(404, "MODEL_NOT_FOUND", "模型不存在"));
        ValidatedModel validated = validate(userId, body, modelId);
        enforceNameUnique(userId, validated.type(), validated.name(), modelId);
        String keyEnc = resolveKeyEnc(existing, body);
        repository.update(modelId, userId, validated.type(), validated.protocol(), validated.name(),
                validated.modelId(), validated.baseUrl(), keyEnc, capabilitiesJson(validated, body), validated.builtinPresetId());
        return repository.findByIdAndUser(modelId, userId).map(this::toDto)
                .orElseThrow(() -> new IllegalStateException("模型更新后读取失败"));
    }

    public void delete(UUID userId, UUID modelId) {
        if (!repository.delete(modelId, userId)) {
            throw new HttpErrorException(404, "MODEL_NOT_FOUND", "模型不存在");
        }
    }

    // ===== server-side resolution (T2.4) =====

    /**
     * Resolve a model's full runtime config (decrypted key) by id and owner —
     * used by task creation and the proxy endpoints. Returns empty when the
     * model is missing or not owned by the user.
     */
    public Optional<ResolvedModel> resolve(UUID userId, String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        UUID id;
        try {
            id = UUID.fromString(modelId.trim());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return repository.findByIdAndUser(id, userId).map(row -> new ResolvedModel(
                row.id(), row.type(), row.protocol(), row.name(), row.modelId(),
                row.baseUrl(), decryptKey(row.apiKeyEnc()), row.builtinPresetId()));
    }

    /** Runtime model view with the decrypted key (never serialized to the client). */
    public record ResolvedModel(UUID id, String type, String protocol, String name, String modelId,
                                String baseUrl, String apiKey, String builtinPresetId) {
    }

    // ===== helpers =====

    private record ValidatedModel(String type, String protocol, String name, String modelId,
                                  String baseUrl, String builtinPresetId) {
    }

    private ValidatedModel validate(UUID userId, JsonNode body, UUID excludeId) {
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
        String builtinPreset = text(body, "builtinPreset");
        return new ValidatedModel(type, protocol, name.trim(), modelId.trim(), baseUrl.trim(), builtinPreset);
    }

    private void enforceNameUnique(UUID userId, String type, String name, UUID excludeId) {
        if (repository.existsName(userId, type, name, excludeId)) {
            throw new HttpErrorException(409, "MODEL_NAME_TAKEN", "同名模型已存在");
        }
    }

    /** New plaintext key → encrypt; masked/empty → null (keep existing). */
    private String encryptKey(JsonNode body) {
        String apiKey = text(body, "apiKey");
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("***")) {
            return null;
        }
        return crypto.encrypt(apiKey.trim());
    }

    /** Update path: masked/empty key keeps the stored ciphertext. */
    private String resolveKeyEnc(ModelRepository.ModelRow existing, JsonNode body) {
        String apiKey = text(body, "apiKey");
        if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("***")) {
            return crypto.encrypt(apiKey.trim());
        }
        return existing.apiKeyEnc();
    }

    private String decryptKey(String apiKeyEnc) {
        if (apiKeyEnc == null) {
            return null;
        }
        try {
            return crypto.decrypt(apiKeyEnc);
        } catch (Exception e) {
            log.warn("[models] 模型密钥解密失败: {}", e.getMessage());
            return null;
        }
    }

    private String capabilitiesJson(ValidatedModel validated, JsonNode body) {
        ObjectNode caps = objectMapper.createObjectNode();
        if ("image".equals(validated.type())) {
            caps.put("max_ref_images", number(body, "maxRefImages", 0));
            caps.put("max_output_size", text(body, "maxOutputSize", "1K"));
            caps.put("supports_advanced_params", booleanValue(body, "supportsAdvancedParams", false));
        } else {
            String note = text(body, "note");
            if (note != null) {
                caps.put("note", note);
            }
        }
        return caps.toString();
    }

    /** Public DTO — apiKey always masked. */
    private Map<String, Object> toDto(ModelRepository.ModelRow row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", row.id().toString());
        dto.put("type", row.type());
        dto.put("protocol", row.protocol());
        dto.put("name", row.name());
        dto.put("modelId", row.modelId());
        dto.put("apiKey", CryptoService.maskApiKey(decryptKey(row.apiKeyEnc())));
        dto.put("baseUrl", row.baseUrl());
        if ("image".equals(row.type())) {
            JsonNode caps = parseCaps(row.capabilitiesJson());
            dto.put("builtinPreset", row.builtinPresetId() != null ? row.builtinPresetId() : text(caps, "builtin_preset", ""));
            dto.put("maxRefImages", caps.hasNonNull("max_ref_images") ? caps.get("max_ref_images").asInt(0) : 0);
            dto.put("maxOutputSize", caps.hasNonNull("max_output_size") ? caps.get("max_output_size").asText() : "1K");
            dto.put("supportsAdvancedParams", caps.path("supports_advanced_params").asBoolean(false));
        } else {
            JsonNode caps = parseCaps(row.capabilitiesJson());
            dto.put("note", caps.hasNonNull("note") ? caps.get("note").asText() : "");
        }
        return dto;
    }

    private JsonNode parseCaps(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
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

    private static boolean booleanValue(JsonNode node, String key, boolean fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isBoolean() ? value.asBoolean() : fallback;
    }
}
