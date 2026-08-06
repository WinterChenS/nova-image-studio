package com.nova.studio.accountpool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.infra.NormalizedError;
import com.nova.studio.settings.CryptoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T3 (WIN-28) — account pool service ({@code ai_accounts}): admin CRUD with
 * AES-GCM key encryption (reused {@link CryptoService}) and masked responses,
 * status transitions (active/paused/broken/deleted — soft delete ADR-28),
 * model_scope existence validation (R1), lightweight connectivity test
 * (success clears failure state), and candidate checks for the catalog
 * availability flag / task pre-check (A1/A4/A5).
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_PAUSED = "paused";
    public static final String STATUS_BROKEN = "broken";
    public static final String STATUS_DELETED = "deleted";

    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(30);

    private final AccountRepository repository;
    private final CatalogModelRepository catalogRepository;
    private final CryptoService crypto;
    private final ObjectMapper objectMapper;
    private final WebClient.Builder webClientBuilder;
    private final Cache<String, List<AccountRepository.Row>> byStatusCache;

    public AccountService(AccountRepository repository,
                          CatalogModelRepository catalogRepository,
                          CryptoService crypto,
                          ObjectMapper objectMapper,
                          WebClient.Builder webClientBuilder) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.crypto = crypto;
        this.objectMapper = objectMapper;
        this.webClientBuilder = webClientBuilder;
        this.byStatusCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1)).maximumSize(10).build();
    }

    // ===== admin CRUD =====

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AccountRepository.Row row : repository.listAll()) {
            result.add(toDto(row));
        }
        return result;
    }

    public Map<String, Object> create(UUID adminId, JsonNode body) {
        Validated valid = validate(body);
        UUID id = repository.insert(valid.name(), valid.protocol(), valid.baseUrl(),
                crypto.encrypt(valid.apiKey()), valid.modelScopeJson(), valid.priority(), valid.remark(), adminId);
        invalidate();
        return repository.findById(id).map(this::toDto)
                .orElseThrow(() -> new IllegalStateException("账号创建后读取失败"));
    }

    public Map<String, Object> update(UUID adminId, UUID id, JsonNode body) {
        AccountRepository.Row existing = require(id);
        Validated valid = validate(body);
        String keyEnc = resolveKeyEnc(existing, body);
        repository.update(id, valid.name(), valid.protocol(), valid.baseUrl(), keyEnc,
                valid.modelScopeJson(), valid.priority(), existing.monthlyCapCost(), valid.remark());
        invalidate();
        return repository.findById(id).map(this::toDto)
                .orElseThrow(() -> new IllegalStateException("账号更新后读取失败"));
    }

    /** Soft delete (ADR-28) — keeps usage_records FK integrity; never schedulable. */
    public void delete(UUID adminId, UUID id) {
        require(id);
        repository.updateStatus(id, STATUS_DELETED);
        invalidate();
    }

    public Map<String, Object> pause(UUID id) {
        AccountRepository.Row row = require(id);
        if (!STATUS_ACTIVE.equals(row.status())) {
            throw new HttpErrorException(400, "INVALID_STATE", "仅 active 账号可停用");
        }
        repository.updateStatus(id, STATUS_PAUSED);
        invalidate();
        return toDto(require(id));
    }

    public Map<String, Object> resume(UUID id) {
        AccountRepository.Row row = require(id);
        if (!STATUS_PAUSED.equals(row.status())) {
            throw new HttpErrorException(400, "INVALID_STATE", "仅 paused 账号可启用");
        }
        repository.updateStatus(id, STATUS_ACTIVE);
        invalidate();
        return toDto(require(id));
    }

    /** Admin recovery from broken (A4/A22) — active + health reset (no auto-recovery). */
    public Map<String, Object> recover(UUID id) {
        AccountRepository.Row row = require(id);
        if (!STATUS_BROKEN.equals(row.status())) {
            throw new HttpErrorException(400, "INVALID_STATE", "仅 broken 账号可恢复");
        }
        repository.updateStatus(id, STATUS_ACTIVE);
        repository.updateHealth(id, AccountHealth.EMPTY.toJson(objectMapper));
        invalidate();
        return toDto(require(id));
    }

    // ===== connectivity test (account.test) =====

    /**
     * Lightweight upstream probe (per-protocol model list call, same shape as
     * the proxy/models handler). Success clears the failure/cooldown state
     * (A4: 测试连通成功清失败计数). Never throws — returns {@code {ok, message}}.
     */
    public Map<String, Object> testConnection(UUID id) {
        AccountRepository.Row row = require(id);
        String apiKey = crypto.decrypt(row.apiKeyEnc());
        if (apiKey == null || apiKey.isBlank()) {
            throw new HttpErrorException(400, "NO_API_KEY", "账号未配置 API Key");
        }
        try {
            String normalizedBaseUrl = ImageGenService.normalizeProtocolBaseUrl(row.protocol(), row.baseUrl());
            Map<String, String> headers = new LinkedHashMap<>();
            String url;
            if ("google".equals(row.protocol()) || "google-gemini".equals(row.protocol())) {
                url = normalizedBaseUrl + "/v1beta/models";
                headers.put("x-goog-api-key", apiKey);
                headers.put("Authorization", "Bearer " + apiKey);
            } else {
                url = normalizedBaseUrl + "/v1/models";
                headers.put("Authorization", "Bearer " + apiKey);
                if ("anthropic-messages".equals(row.protocol())) {
                    headers.put("x-api-key", apiKey);
                    headers.put("anthropic-version", "2023-06-01");
                }
            }
            record StatusBody(int status, String text) {
            }
            StatusBody result = webClientBuilder.build().get()
                    .uri(url)
                    .headers(h -> headers.forEach(h::set))
                    .exchangeToMono(resp -> resp.bodyToMono(String.class)
                            .map(text -> new StatusBody(resp.statusCode().value(), text)))
                    .block(TEST_TIMEOUT);
            if (result == null || result.status() >= 300) {
                return Map.of("ok", false, "message", "上游返回 " + (result == null ? "无响应" : result.status()));
            }
            repository.updateHealth(id, AccountHealth.EMPTY.toJson(objectMapper));   // 成功清失败计数
            invalidate();
            return Map.of("ok", true, "message", "连接成功");
        } catch (Exception e) {
            return Map.of("ok", false, "message", NormalizedError.normalize(e, TEST_TIMEOUT.toMillis()));
        }
    }

    // ===== scheduler / catalog integration =====

    /** Cached active accounts (scheduler candidate pool, ADR-23). */
    public List<AccountRepository.Row> activeAccounts() {
        return byStatusCache.get(STATUS_ACTIVE, k -> repository.findAllByStatus(STATUS_ACTIVE));
    }

    /**
     * A5 pre-check: is there at least one schedulable account for this model?
     * active + protocol match (ADR-34) + model_scope empty/contains + cooldown
     * expired. Used by the public catalog {@code available} flag and the task
     * creation pre-check (fast fail, no slot reservation).
     */
    public boolean hasCandidate(CatalogModelRepository.Row model) {
        for (AccountRepository.Row account : activeAccounts()) {
            if (isCandidate(account, model)) {
                return true;
            }
        }
        return false;
    }

    public boolean isCandidate(AccountRepository.Row account, CatalogModelRepository.Row model) {
        if (!STATUS_ACTIVE.equals(account.status())) {
            return false;
        }
        if (!model.protocol().equals(account.protocol())) {
            return false;   // ADR-34: 协议交叉校验
        }
        List<UUID> scope = parseScope(account.modelScopeJson());
        if (!scope.isEmpty() && !scope.contains(model.id())) {
            return false;   // R1: scope 非空时仅含模型 id 者入选
        }
        AccountHealth health = AccountHealth.parse(account.healthJson(), objectMapper);
        return !health.coolingDown();
    }

    /** ADR-28: catalog model delete strips its id from every account's scope. */
    public void removeModelFromScopes(UUID modelId) {
        for (AccountRepository.Row account : repository.listAll()) {
            List<UUID> scope = parseScope(account.modelScopeJson());
            if (scope.contains(modelId)) {
                scope.remove(modelId);
                ArrayNode array = objectMapper.createArrayNode();
                scope.forEach(id -> array.add(id.toString()));
                repository.updateScope(account.id(), array.toString());
            }
        }
        invalidate();
    }

    // ===== health hooks (used by AccountHealthService, T4) =====

    public void markBroken(UUID id) {
        repository.updateStatus(id, STATUS_BROKEN);
        invalidate();
    }

    public void updateHealth(UUID id, AccountHealth health) {
        repository.updateHealth(id, health.toJson(objectMapper));
        invalidate();
    }

    public Optional<AccountRepository.Row> findById(UUID id) {
        return repository.findById(id);
    }

    /** Decrypt the account's API key (scheduler dispatch / connectivity test). */
    public String decryptKey(AccountRepository.Row row) {
        return crypto.decrypt(row.apiKeyEnc());
    }

    // ===== helpers =====

    private AccountRepository.Row require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "账号不存在"));
    }

    private record Validated(String name, String protocol, String baseUrl, String apiKey,
                             String modelScopeJson, Integer priority, String remark) {
    }

    private Validated validate(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String name = text(body, "name");
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("账号名称不能为空");
        }
        String protocol = text(body, "protocol");
        boolean protocolOk = CatalogModelService.IMAGE_PROTOCOLS.contains(protocol)
                || CatalogModelService.TEXT_PROTOCOLS.contains(protocol);
        if (!protocolOk) {
            throw new IllegalArgumentException("协议类型无效");
        }
        String baseUrl = text(body, "baseUrl");
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new IllegalArgumentException("API 基础地址不能为空");
        }
        String apiKey = text(body, "apiKey");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException("API Key 不能为空");
        }
        // R1: model_scope 内每个 id 必须是目录中存在模型
        JsonNode scopeNode = body.get("modelScope");
        String scopeJson = "[]";
        if (scopeNode != null && scopeNode.isArray()) {
            List<UUID> scope = new ArrayList<>();
            for (JsonNode item : scopeNode) {
                String raw = item.isTextual() ? item.asText() : null;
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                UUID modelId = parseUuid(raw, "模型作用域包含无效的模型 ID");
                if (catalogRepository.findById(modelId).isEmpty()) {
                    throw new IllegalArgumentException("模型作用域包含不存在的模型 ID");
                }
                scope.add(modelId);
            }
            ArrayNode array = objectMapper.createArrayNode();
            scope.forEach(id -> array.add(id.toString()));
            scopeJson = array.toString();
        }
        Integer priority = body.hasNonNull("priority") ? body.get("priority").asInt(100) : 100;
        String remark = text(body, "remark");
        return new Validated(name.trim(), protocol, baseUrl.trim(), apiKey.trim(),
                scopeJson, priority, remark);
    }

    private String resolveKeyEnc(AccountRepository.Row existing, JsonNode body) {
        String apiKey = text(body, "apiKey");
        if (apiKey != null && !apiKey.isBlank() && !apiKey.contains("***")) {
            return crypto.encrypt(apiKey.trim());
        }
        return existing.apiKeyEnc();    // masked/empty → keep stored ciphertext
    }

    private List<UUID> parseScope(String scopeJson) {
        List<UUID> result = new ArrayList<>();
        if (scopeJson == null || scopeJson.isBlank()) {
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(scopeJson);
            if (node.isArray()) {
                for (JsonNode item : node) {
                    if (item.isTextual()) {
                        try {
                            result.add(UUID.fromString(item.asText()));
                        } catch (IllegalArgumentException ignored) {
                            // 脏数据跳过（服务层校验已防写入）
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[account] model_scope 解析失败: {}", e.getMessage());
        }
        return result;
    }

    /** DTO — apiKey always masked ({@code sk-***last4}); health passed through. */
    private Map<String, Object> toDto(AccountRepository.Row row) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", row.id().toString());
        dto.put("name", row.name());
        dto.put("protocol", row.protocol());
        dto.put("baseUrl", row.baseUrl());
        dto.put("apiKey", CryptoService.maskApiKey(crypto.decrypt(row.apiKeyEnc())));
        List<UUID> scope = parseScope(row.modelScopeJson());
        List<String> scopeStrings = scope.stream().map(UUID::toString).toList();
        dto.put("modelScope", scopeStrings);
        dto.put("status", row.status());
        dto.put("priority", row.priority());
        dto.put("health", parseHealth(row.healthJson()));
        dto.put("remark", row.remark());
        dto.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        dto.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        return dto;
    }

    private Map<String, Object> parseHealth(String healthJson) {
        AccountHealth health = AccountHealth.parse(healthJson, objectMapper);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("consecutiveFailures", health.consecutiveFailures());
        out.put("cooldownUntil", health.cooldownUntil() == null ? null : health.cooldownUntil().toString());
        out.put("lastError", health.lastError());
        out.put("lastSuccessAt", health.lastSuccessAt() == null ? null : health.lastSuccessAt().toString());
        return out;
    }

    private static UUID parseUuid(String raw, String message) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message);
        }
    }

    private void invalidate() {
        byStatusCache.invalidateAll();
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

}
