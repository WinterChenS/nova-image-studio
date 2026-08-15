package com.nova.studio.history;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WIN-39 (WIN-41 T12/T13, ADR-39/42) — 统一历史服务（histories 表）：
 * <ul>
 *   <li><b>反推</b>（type=reverse）：completed 双槽（最近两条）+ draft 草稿（每用户至多一条，
 *       输入图 assets 引用）+ 历史列表/删除；</li>
 *   <li><b>GIF</b>（type=gif）：状态机（idle→generating_grid→review_grid→generating_gif→done|failed，
 *       任意→failed）+ 网格/成品 assets + 中断恢复（状态查询）+ 历史结果列表/删除；</li>
 * </ul>
 * 属主隔离（AC-10）、配额滚动清理（limit.historyCapReverse=500 / limit.historyCapGif=100）。
 */
@Service
public class HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryService.class);

    public static final String TYPE_REVERSE = "reverse";
    public static final String TYPE_GIF = "gif";

    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_IDLE = "idle";
    public static final String STATUS_GENERATING_GRID = "generating_grid";
    public static final String STATUS_REVIEW_GRID = "review_grid";
    public static final String STATUS_GENERATING_GIF = "generating_gif";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_FAILED = "failed";

    public static final String SOURCE_KIND_GIF = "gif";
    public static final String SOURCE_KIND_REVERSE = "reverse-prompt";

    private final HistoryRepository repository;
    private final AssetService assetService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public HistoryService(HistoryRepository repository, AssetService assetService,
                          SettingsService settingsService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.assetService = assetService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    // ===== 反推（T12）=====

    /** 保存反推 completed 记录（双槽语义由查询端 ORDER BY created_at DESC LIMIT 2 承载）。 */
    public HistoryRepository.HistoryRow saveReverseRecord(UUID userId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String text = body.hasNonNull("text") ? body.get("text").asText() : "";
        if (text.isBlank()) {
            throw new IllegalArgumentException("反推结果不能为空");
        }
        Instant now = Instant.now();
        HistoryEntity entity = new HistoryEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId.toString());
        entity.setType(TYPE_REVERSE);
        entity.setStatus(STATUS_COMPLETED);
        entity.setTitle(truncateTitle(text));
        entity.setPayload(payloadOf(Map.of(
                "text", text,
                "model", body.hasNonNull("model") ? body.get("model").asText() : "",
                "mode", body.hasNonNull("mode") ? body.get("mode").asText() : "")));
        entity.setImageIds("[]");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.insert(entity);
        rollbackHistoryCap(userId, TYPE_REVERSE, SettingsService.DEFAULT_HISTORY_CAP_REVERSE,
                SettingsService.KEY_HISTORY_CAP_REVERSE);
        log.info("[history] 反推记录保存: user={}, history={}", userId, entity.getId());
        return repository.findByIdAndOwner(entity.getId(), userId).map(HistoryRepository::toRow).orElseThrow();
    }

    /** 反推双槽：最近 limit 条 completed（ADR-39；默认 2）。 */
    public List<HistoryRepository.HistoryRow> listReverseRecords(UUID userId, int limit) {
        return repository.listCompletedRecent(userId, TYPE_REVERSE,
                limit <= 0 ? 2 : Math.min(limit, 10)).stream().map(HistoryRepository::toRow).toList();
    }

    /** 反推草稿 upsert（每用户至多一条；输入图 assetId 引用）。空 text + 空 image → 清除草稿。 */
    public HistoryRepository.HistoryRow saveReverseDraft(UUID userId, JsonNode body) {
        String text = body != null && body.hasNonNull("text") ? body.get("text").asText() : "";
        List<String> imageIds = body != null ? textArray(body, "imageIds") : List.of();
        Instant now = Instant.now();
        HistoryEntity existing = repository.findReverseDraft(userId).orElse(null);
        if (text.isBlank() && imageIds.isEmpty()) {
            if (existing != null) {
                repository.deleteByIdAndOwner(existing.getId(), userId);
                log.info("[history] 反推草稿清除: user={}", userId);
            }
            return null;
        }
        HistoryEntity entity = existing != null ? existing : new HistoryEntity();
        if (existing == null) {
            entity.setId(UUID.randomUUID().toString());
            entity.setUserId(userId.toString());
            entity.setType(TYPE_REVERSE);
            entity.setStatus(STATUS_DRAFT);
            entity.setCreatedAt(now);
        }
        entity.setPayload(payloadOf(Map.of(
                "text", text,
                "model", body != null && body.hasNonNull("model") ? body.get("model").asText() : "",
                "mode", body != null && body.hasNonNull("mode") ? body.get("mode").asText() : "")));
        entity.setImageIds(toJsonArray(imageIds));
        entity.setUpdatedAt(now);
        if (existing == null) {
            repository.insert(entity);
        } else {
            repository.update(entity);
        }
        log.info("[history] 反推草稿保存: user={}, draft={}, images={}", userId, entity.getId(), imageIds.size());
        return repository.findByIdAndOwner(entity.getId(), userId).map(HistoryRepository::toRow).orElseThrow();
    }

    public HistoryRepository.HistoryRow getReverseDraft(UUID userId) {
        return repository.findReverseDraft(userId).map(HistoryRepository::toRow).orElse(null);
    }

    // ===== GIF（T13）=====

    /** 创建 GIF job（status=idle；参考图/参数入 payload）。 */
    public HistoryRepository.HistoryRow createGifJob(UUID userId, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String prompt = body.hasNonNull("prompt") ? body.get("prompt").asText() : "";
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("GIF 提示词不能为空");
        }
        Instant now = Instant.now();
        HistoryEntity entity = new HistoryEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setUserId(userId.toString());
        entity.setType(TYPE_GIF);
        entity.setStatus(STATUS_IDLE);
        entity.setTitle(truncateTitle(prompt));
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("prompt", prompt);
        payload.put("model", body.hasNonNull("model") ? body.get("model").asText() : "");
        payload.put("loop", body.hasNonNull("loop") && body.get("loop").asBoolean());
        payload.put("closedLoop", body.hasNonNull("closedLoop") && body.get("closedLoop").asBoolean());
        payload.put("frameDelayMs", body.hasNonNull("frameDelayMs") ? body.get("frameDelayMs").asInt() : 120);
        payload.put("loopCount", body.hasNonNull("loopCount") ? body.get("loopCount").asInt() : 0);
        payload.put("framePadding", body.hasNonNull("framePadding") ? body.get("framePadding").asDouble() : 1.5);
        putTextIfPresent(payload, "gptImageQuality", body, "gptImageQuality");
        putTextIfPresent(payload, "gptImageStyle", body, "gptImageStyle");
        putTextIfPresent(payload, "gptImageBackground", body, "gptImageBackground");
        List<String> refImageIds = textArray(body, "refImageAssetIds");
        if (!refImageIds.isEmpty()) {
            payload.set("refImageAssetIds", objectMapper.valueToTree(refImageIds));
        }
        payload.put("encodeMode", "client");   // ADR-43 预留演进字段
        entity.setPayload(payload.toString());
        entity.setImageIds("[]");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.insert(entity);
        log.info("[history] GIF job 创建: user={}, history={}", userId, entity.getId());
        return repository.findByIdAndOwner(entity.getId(), userId).map(HistoryRepository::toRow).orElseThrow();
    }

    /** GIF job 状态查询（中断恢复：网格生成中/编码中刷新或换端按服务端状态恢复，AC-4）。 */
    public HistoryRepository.HistoryRow getGifJob(UUID userId, String id) {
        return getOwned(userId, id, TYPE_GIF);
    }

    /** GIF 状态变迁（轻量 PATCH，ADR-39）：非法迁移 → 409。 */
    public HistoryRepository.HistoryRow patchGifJob(UUID userId, String id, JsonNode body) {
        HistoryRepository.HistoryRow row = getOwned(userId, id, TYPE_GIF);
        if (body == null || !body.isObject() || !body.hasNonNull("status")) {
            throw new IllegalArgumentException("缺少 status");
        }
        String next = body.get("status").asText();
        if (!List.of(STATUS_GENERATING_GRID, STATUS_REVIEW_GRID, STATUS_GENERATING_GIF,
                STATUS_DONE, STATUS_FAILED).contains(next)) {
            throw new IllegalArgumentException("GIF 状态无效");
        }
        if (!validTransition(row.status(), next)) {
            throw new HttpErrorException(409, "INVALID_GIF_TRANSITION",
                    "GIF 状态迁移无效：" + row.status() + " → " + next);
        }
        HistoryEntity patch = new HistoryEntity();
        patch.setId(id);
        patch.setStatus(next);
        patch.setUpdatedAt(Instant.now());
        if (body.hasNonNull("taskId")) {
            patch.setTaskId(body.get("taskId").asText());
        }
        if (body.hasNonNull("gridImageAssetId")) {
            String gridAssetId = body.get("gridImageAssetId").asText();
            patch.setImageIds(toJsonArray(List.of(gridAssetId)));
        }
        if (body.hasNonNull("error")) {
            patch.setError(body.get("error").asText());
        }
        repository.update(patch);
        log.info("[history] GIF 状态变迁: user={}, history={}, {} → {}", userId, id, row.status(), next);
        return repository.findByIdAndOwner(id, userId).map(HistoryRepository::toRow).orElseThrow();
    }

    /** 上传 GIF 成品（multipart → assets source_kind='gif'，image_ids 追加，status→done）。 */
    public HistoryRepository.HistoryRow uploadGifResult(UUID userId, String id, byte[] bytes,
                                                        String mimeType) {
        HistoryRepository.HistoryRow row = getOwned(userId, id, TYPE_GIF);
        if (!List.of(STATUS_GENERATING_GIF, STATUS_REVIEW_GRID, STATUS_DONE).contains(row.status())) {
            throw new HttpErrorException(409, "INVALID_GIF_STATE", "当前状态不可上传成品");
        }
        AssetRepository.AssetRow asset = assetService.createImageIdempotent(
                userId, null, "GIF 成品", List.of(), null, SOURCE_KIND_GIF, "GIF 成品",
                id, null, bytes, mimeType == null ? "image/gif" : mimeType,
                null, null, Instant.now(), "{\"subtype\":\"result\"}");
        List<String> imageIds = new ArrayList<>(parseJsonArray(row.imageIds()));
        if (!imageIds.contains(asset.id())) {
            imageIds.add(asset.id());
        }
        HistoryEntity patch = new HistoryEntity();
        patch.setId(id);
        patch.setStatus(STATUS_DONE);
        patch.setImageIds(toJsonArray(imageIds));
        patch.setUpdatedAt(Instant.now());
        repository.update(patch);
        log.info("[history] GIF 成品上传: user={}, history={}, asset={}", userId, id, asset.id());
        return repository.findByIdAndOwner(id, userId).map(HistoryRepository::toRow).orElseThrow();
    }

    // ===== 统一历史列表/详情/删除（C5/C6）=====

    public HistoryRepository.HistoryPage listHistories(UUID userId, String type, String before, int limit) {
        if (!List.of(TYPE_REVERSE, TYPE_GIF).contains(type)) {
            throw new IllegalArgumentException("历史类型无效");
        }
        Instant cursor = parseCursor(before);
        return repository.listByType(userId, type, cursor, limit);
    }

    public HistoryRepository.HistoryRow getHistory(UUID userId, String id) {
        return repository.findByIdAndOwner(id, userId)
                .map(HistoryRepository::toRow)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "记录不存在"));
    }

    /** 删除（gif 联动软删关联 assets/对象，ADR-42；反推/GIF 为低价值记录直接硬删）。 */
    public void deleteHistory(UUID userId, String id) {
        HistoryRepository.HistoryRow row = getHistory(userId, id);
        for (String assetId : parseJsonArray(row.imageIds())) {
            try {
                assetService.delete(userId, assetId);
            } catch (Exception e) {
                log.warn("[history] 删除联动素材失败（尽力而为）: user={}, asset={}: {}",
                        userId, assetId, e.getMessage());
            }
        }
        repository.deleteByIdAndOwner(id, userId);
        log.info("[history] 删除记录: user={}, history={}, type={}", userId, id, row.type());
    }

    // ===== JSON 形状 =====

    public Map<String, Object> toJson(HistoryRepository.HistoryRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("type", row.type());
        map.put("status", row.status());
        map.put("title", row.title());
        map.put("payload", parseJson(row.payload()));
        map.put("imageIds", parseJsonArray(row.imageIds()));
        map.put("taskId", row.taskId());
        map.put("error", row.error());
        map.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        map.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        return map;
    }

    /** 素材字节读取（属主校验，供历史图片访问）。 */
    public AssetService.StoredAssetFile getImage(UUID userId, String assetId) {
        return assetService.getFile(userId, assetId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "图片不存在"));
    }

    // ===== helpers =====

    private HistoryRepository.HistoryRow getOwned(UUID userId, String id, String type) {
        HistoryRepository.HistoryRow row = getHistory(userId, id);
        if (!type.equals(row.type())) {
            throw new HttpErrorException(404, "NOT_FOUND", "记录不存在");
        }
        return row;
    }

    private void rollbackHistoryCap(UUID userId, String type, int defaultCap, String settingsKey) {
        try {
            int cap = settingsService.getInt(userId, settingsKey, defaultCap);
            int deleted = repository.deleteOldestBeyondCap(userId, type, cap);
            if (deleted > 0) {
                log.info("[history] 超量滚动清理: user={}, type={}, deleted={}", userId, type, deleted);
            }
        } catch (Exception e) {
            log.warn("[history] 滚动清理失败（尽力而为）: {}", e.getMessage());
        }
    }

    private static boolean validTransition(String from, String to) {
        if (STATUS_FAILED.equals(from)) {
            return false;
        }
        return switch (from) {
            case STATUS_IDLE -> List.of(STATUS_GENERATING_GRID, STATUS_FAILED).contains(to);
            case STATUS_GENERATING_GRID -> List.of(STATUS_REVIEW_GRID, STATUS_FAILED).contains(to);
            case STATUS_REVIEW_GRID -> List.of(STATUS_GENERATING_GIF, STATUS_FAILED).contains(to);
            case STATUS_GENERATING_GIF -> List.of(STATUS_DONE, STATUS_FAILED).contains(to);
            case STATUS_DONE -> List.of(STATUS_FAILED).contains(to);   // 允许重跑失败标记
            default -> false;
        };
    }

    private static String truncateTitle(String text) {
        String trimmed = text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= 24) {
            return trimmed;
        }
        return trimmed.substring(0, 24) + "…";
    }

    private String payloadOf(Map<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String toJsonArray(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            List<String> result = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> result.add(item.asText()));
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }

    private Object parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static void putTextIfPresent(ObjectNode payload, String key, JsonNode body, String field) {
        if (body != null && body.hasNonNull(field)) {
            payload.put(key, body.get(field).asText());
        }
    }

    private static List<String> textArray(JsonNode body, String key) {
        List<String> result = new ArrayList<>();
        if (body != null && body.has(key) && body.get(key).isArray()) {
            body.get(key).forEach(node -> {
                if (node.isTextual()) {
                    result.add(node.asText());
                }
            });
        }
        return result;
    }

    private static Instant parseCursor(String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(before);
        } catch (Exception e) {
            throw new IllegalArgumentException("游标格式无效（应为 ISO 时间戳）");
        }
    }
}
