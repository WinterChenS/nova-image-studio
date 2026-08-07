package com.nova.studio.canvas;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * WIN-39 (WIN-40 T5) — 画布项目服务：projects CRUD + 整文档 PUT（version 自增，
 * A8 阶段1 last-write-wins）+ 软删/恢复（回收站，C8）+ 画布图片上传（assets
 * source_kind='canvas'，ADR-35 素材引用化）。配额 limit.canvasProjectCap（AC-11）。
 *
 * <p>属主隔离：全部读取/变更经 findByIdAndOwner（跨用户 404，AC-10）。
 */
@Service
public class CanvasService {

    private static final Logger log = LoggerFactory.getLogger(CanvasService.class);

    public static final String SOURCE_KIND_CANVAS = "canvas";
    public static final int DEFAULT_PROJECT_CAP = SettingsService.DEFAULT_CANVAS_PROJECT_CAP;

    private final CanvasProjectRepository repository;
    private final AssetService assetService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public CanvasService(CanvasProjectRepository repository,
                         AssetService assetService,
                         SettingsService settingsService,
                         ObjectMapper objectMapper) {
        this.repository = repository;
        this.assetService = assetService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    // ===== CRUD =====

    /** 新建项目（配额校验 AC-11）；body.id 可选（前端/迁移提供稳定客户端 id）。 */
    public CanvasProjectRepository.CanvasRow create(UUID userId, String title, String clientId) {
        int cap = settingsService.getInt(userId, SettingsService.KEY_CANVAS_PROJECT_CAP, DEFAULT_PROJECT_CAP);
        long active = repository.countActive(userId);
        if (active >= cap) {
            throw new HttpErrorException(409, "QUOTA_EXCEEDED",
                    "画布项目数量已达上限（" + cap + "），请删除或清理回收站");
        }
        Instant now = Instant.now();
        CanvasProjectEntity entity = new CanvasProjectEntity();
        entity.setId(clientId != null && !clientId.isBlank() ? clientId : UUID.randomUUID().toString());
        entity.setUserId(userId.toString());
        entity.setTitle(title != null && !title.isBlank() ? title.trim() : "未命名画布");
        entity.setNodes("[]");
        entity.setConnections("[]");
        entity.setBackgroundMode("lines");
        entity.setShowImageInfo(false);
        entity.setViewport("{\"x\":0,\"y\":0,\"k\":1}");
        entity.setVersion(1L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        repository.insert(entity);
        log.info("[canvas] 新建项目: user={}, project={}", userId, entity.getId());
        return repository.findByIdAndOwner(entity.getId(), userId).orElseThrow();
    }

    /** 兼容签名（无客户端 id）。 */
    public CanvasProjectRepository.CanvasRow create(UUID userId, String title) {
        return create(userId, title, null);
    }

    public List<CanvasProjectRepository.CanvasRow> list(UUID userId, boolean includeDeleted) {
        return repository.list(userId, includeDeleted);
    }

    public CanvasProjectRepository.CanvasRow get(UUID userId, String projectId) {
        return getOwned(userId, projectId);
    }

    /**
     * 整文档保存（PUT，防抖提交语义）：nodes/connections/viewport/背景；
     * version 自增（A8：阶段1 不校验传入 version，last-write-wins）。
     */
    public CanvasProjectRepository.CanvasRow saveDocument(UUID userId, String projectId, JsonNode body) {
        CanvasProjectRepository.CanvasRow row = getOwned(userId, projectId);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        CanvasProjectEntity patch = new CanvasProjectEntity();
        patch.setId(projectId);
        patch.setUpdatedAt(Instant.now());
        patch.setVersion(row.version() == null ? 1L : row.version() + 1);
        if (body.has("nodes")) {
            patch.setNodes(validateJson(body.get("nodes"), "nodes"));
        }
        if (body.has("connections")) {
            patch.setConnections(validateJson(body.get("connections"), "connections"));
        }
        if (body.has("viewport")) {
            patch.setViewport(validateJson(body.get("viewport"), "viewport"));
        }
        if (body.hasNonNull("backgroundMode")) {
            patch.setBackgroundMode(body.get("backgroundMode").asText());
        }
        if (body.hasNonNull("title")) {
            String title = body.get("title").asText();
            if (title.isBlank()) {
                throw new IllegalArgumentException("项目标题不能为空");
            }
            patch.setTitle(title.trim());
        }
        repository.saveDocument(patch);
        // WIN-39 (G1 修复): 画布节点图片引用变更 → ref_count 差值调整（A7 引用保护）
        List<String> nextAssetIds = extractNodeAssetIds(body.has("nodes") ? body.get("nodes").toString() : null);
        List<String> prevAssetIds = extractNodeAssetIds(row.nodes());
        adjustRefCountDelta(userId, prevAssetIds, nextAssetIds);
        log.info("[canvas] 保存画布文档: user={}, project={}, version={}",
                userId, projectId, patch.getVersion());
        return getOwned(userId, projectId);
    }

    /** 轻量字段更新（PATCH：title/背景/设置）。 */
    public CanvasProjectRepository.CanvasRow patch(UUID userId, String projectId, JsonNode body) {
        getOwned(userId, projectId);
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        CanvasProjectEntity patch = new CanvasProjectEntity();
        patch.setId(projectId);
        patch.setUpdatedAt(Instant.now());
        if (body.hasNonNull("title")) {
            String title = body.get("title").asText();
            if (title.isBlank()) {
                throw new IllegalArgumentException("项目标题不能为空");
            }
            patch.setTitle(title.trim());
        }
        if (body.hasNonNull("backgroundMode")) {
            patch.setBackgroundMode(body.get("backgroundMode").asText());
        }
        if (body.hasNonNull("showImageInfo")) {
            patch.setShowImageInfo(body.get("showImageInfo").asBoolean());
        }
        repository.patch(patch);
        return getOwned(userId, projectId);
    }

    /** 软删 → 回收站（C8）；幂等。 */
    public void softDelete(UUID userId, String projectId) {
        CanvasProjectRepository.CanvasRow row = getOwned(userId, projectId);
        if (row.deletedAt() == null) {
            repository.softDelete(projectId, Instant.now());
        }
        log.info("[canvas] 软删项目(回收站): user={}, project={}", userId, projectId);
    }

    /** 回收站恢复（清 deleted_at）。 */
    public void restore(UUID userId, String projectId) {
        CanvasProjectRepository.CanvasRow row = getOwned(userId, projectId);
        if (row.deletedAt() != null) {
            repository.restore(projectId);
        }
        log.info("[canvas] 恢复项目: user={}, project={}", userId, projectId);
    }

    // ===== 画布图片（assets source_kind='canvas'，ADR-35 素材引用化）=====

    /**
     * 画布图片上传（multipart → assets）。A3 默认「引用」语义：素材归用户所有，
     * 节点以 assets.id 引用；跨项目复用 = 同一 assets 行被多个节点引用。
     */
    public AssetRepository.AssetRow uploadImage(UUID userId, byte[] bytes, String mimeType,
                                                String name, Integer width, Integer height) {
        return assetService.createImage(userId, null, name, List.of(), null,
                SOURCE_KIND_CANVAS, "画布图片", null, null,
                bytes, mimeType, width, height, Instant.now(), "{}");
    }

    // ===== helpers =====

    public CanvasProjectRepository.CanvasRow getOwned(UUID userId, String projectId) {
        return repository.findByIdAndOwner(projectId, userId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "画布项目不存在"));
    }

    private String validateJson(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() && !node.isObject()) {
            throw new IllegalArgumentException(field + " 必须是 JSON 数组或对象");
        }
        return node.toString();
    }

    /** Public JSON shape（含 version，供前端冲突检测 P2 使用）。 */
    public Map<String, Object> toJson(CanvasProjectRepository.CanvasRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("title", row.title());
        map.put("nodes", parseJson(row.nodes()));
        map.put("connections", parseJson(row.connections()));
        map.put("backgroundMode", row.backgroundMode());
        map.put("showImageInfo", Boolean.TRUE.equals(row.showImageInfo()));
        map.put("viewport", parseJson(row.viewport()));
        map.put("version", row.version() == null ? 1L : row.version());
        map.put("deletedAt", row.deletedAt() == null ? null : row.deletedAt().toString());
        map.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        map.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        return map;
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

    // ===== 引用计数（A7，G1 修复）=====

    /** 从节点 JSON 中提取素材引用（metadata.storageKey = assetId，ADR-35 素材引用化）。 */
    private List<String> extractNodeAssetIds(String nodesJson) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try {
            JsonNode nodes = objectMapper.readTree(nodesJson);
            if (nodes != null && nodes.isArray()) {
                for (JsonNode node : nodes) {
                    JsonNode metadata = node.get("metadata");
                    if (metadata != null && metadata.hasNonNull("storageKey")) {
                        String key = metadata.get("storageKey").asText();
                        // 仅统计服务端 assetId（UUID 或 assets/ 前缀）；本地 blob 引用不算
                        if (isAssetIdRef(key)) {
                            ids.add(key);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 解析失败不阻断保存（引用计数尽力而为）
        }
        return ids.stream().distinct().toList();
    }

    private boolean isAssetIdRef(String key) {
        return key != null && (key.matches("[0-9a-fA-F-]{36}") || key.startsWith("assets/"));
    }

    private void adjustRefCountDelta(UUID userId, List<String> prevIds, List<String> nextIds) {
        List<String> added = nextIds.stream().filter(id -> !prevIds.contains(id)).toList();
        List<String> removed = prevIds.stream().filter(id -> !nextIds.contains(id)).toList();
        if (!added.isEmpty()) {
            assetService.adjustRefCounts(userId, added, 1);
        }
        if (!removed.isEmpty()) {
            assetService.adjustRefCounts(userId, removed, -1);
        }
    }
}
