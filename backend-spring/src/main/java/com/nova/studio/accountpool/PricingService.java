package com.nova.studio.accountpool;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nova.studio.audit.AuditLogService;
import com.nova.studio.infra.HttpErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T9 (WIN-28) — model price table service ({@code ai_model_pricing}, dual
 * rate B3: per_request_price + price_per_token). Cost is snapshotted at usage
 * write time via {@link #computeCost} — later price edits never change
 * historical usage records (A8). WIN-29 (T29/A16) — upsert/delete are written
 * to {@code audit_log}.
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    private record PricingKey(UUID modelId, String currency) {
    }

    private final PricingRepository repository;
    private final CatalogModelRepository catalogRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLog;
    private final Cache<PricingKey, Optional<PricingRepository.Row>> cache;

    public PricingService(PricingRepository repository,
                          CatalogModelRepository catalogRepository,
                          ObjectMapper objectMapper,
                          AuditLogService auditLog) {
        this.repository = repository;
        this.catalogRepository = catalogRepository;
        this.objectMapper = objectMapper;
        this.auditLog = auditLog;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1)).maximumSize(10_000).build();
    }

    // ===== admin CRUD =====

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PricingRepository.Row row : repository.listAll()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", row.id().toString());
            dto.put("modelId", row.modelId().toString());
            dto.put("modelName", catalogRepository.findById(row.modelId())
                    .map(m -> m.name()).orElse("（已删除模型）"));
            dto.put("currency", row.currency());
            dto.put("perRequestPrice", row.perRequestPrice());
            dto.put("pricePerToken", row.pricePerToken());
            result.add(dto);
        }
        return result;
    }

    public Map<String, Object> upsert(UUID adminId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String modelIdRaw = text(body, "modelId");
        if (modelIdRaw == null || modelIdRaw.isBlank()) {
            throw new IllegalArgumentException("modelId 不能为空");
        }
        UUID modelId = parseUuid(modelIdRaw, "modelId 无效");
        if (catalogRepository.findById(modelId).isEmpty()) {
            throw new HttpErrorException(404, "MODEL_NOT_FOUND", "模型不存在");
        }
        String currency = text(body, "currency") == null ? "CNY" : text(body, "currency").trim().toUpperCase();
        if (currency.isEmpty() || currency.length() > 8) {
            throw new IllegalArgumentException("币种无效");
        }
        BigDecimal perRequest = decimal(body, "perRequestPrice");
        BigDecimal perToken = decimal(body, "pricePerToken");
        if ((perRequest != null && perRequest.signum() < 0) || (perToken != null && perToken.signum() < 0)) {
            throw new IllegalArgumentException("价格不能为负");
        }
        repository.upsert(modelId, currency, perRequest, perToken, adminId);
        cache.invalidate(new PricingKey(modelId, currency));
        auditLog.record(adminId, "pricing.upsert", "ai_model_pricing", modelId.toString(), Map.of(
                "currency", currency,
                "perRequestPrice", perRequest == null ? "" : perRequest.toPlainString(),
                "pricePerToken", perToken == null ? "" : perToken.toPlainString()));
        return Map.of("modelId", modelId.toString(), "currency", currency,
                "perRequestPrice", perRequest, "pricePerToken", perToken);
    }

    public void delete(UUID adminId, UUID modelId, String currency) {
        String cur = currency == null || currency.isBlank() ? "CNY" : currency.trim().toUpperCase();
        if (repository.deleteByModelAndCurrency(modelId, cur) == 0) {
            throw new HttpErrorException(404, "NOT_FOUND", "价格配置不存在");
        }
        cache.invalidate(new PricingKey(modelId, cur));
        auditLog.record(adminId, "pricing.delete", "ai_model_pricing", modelId.toString(),
                Map.of("currency", cur));
    }

    // ===== cost snapshot (B3 / A8) =====

    /** Current pricing row for (model, currency), Caffeine 1s cache. */
    public Optional<PricingRepository.Row> getPricing(UUID modelId, String currency) {
        String cur = currency == null || currency.isBlank() ? "CNY" : currency;
        return cache.get(new PricingKey(modelId, cur), k -> repository.findByModelAndCurrency(modelId, cur));
    }

    /**
     * Snapshot cost: {@code cost = COALESCE(per_request_price,0) + tokens ×
     * COALESCE(price_per_token,0)}; no pricing row → 0 (H7). Tokens null
     * (image models) → per-request only.
     */
    public BigDecimal computeCost(UUID modelId, Long inputTokens, Long outputTokens, String currency) {
        Optional<PricingRepository.Row> pricing = getPricing(modelId, currency);
        if (pricing.isEmpty()) {
            return BigDecimal.ZERO;
        }
        PricingRepository.Row row = pricing.get();
        BigDecimal cost = row.perRequestPrice() == null ? BigDecimal.ZERO : row.perRequestPrice();
        long tokens = (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
        if (tokens > 0 && row.pricePerToken() != null) {
            cost = cost.add(BigDecimal.valueOf(tokens).multiply(row.pricePerToken()));
        }
        return cost;
    }

    private static BigDecimal decimal(JsonNode body, String key) {
        JsonNode value = body.get(key);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber() && !value.isTextual()) {
            throw new IllegalArgumentException(key + " 必须为数字");
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须为数字");
        }
    }

    private static UUID parseUuid(String raw, String message) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}
