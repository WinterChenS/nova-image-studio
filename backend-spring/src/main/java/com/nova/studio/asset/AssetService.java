package com.nova.studio.asset;

import com.nova.studio.asset.AssetRepository.AssetRow;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.project.ProjectService;
import com.nova.studio.storage.ObjectStorageManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * WIN-22 (F-10..F-21 / D.2 / ADR-16/17/18) — server-side asset management:
 * metadata in {@code assets} (PostgreSQL), binary in object storage under the
 * stable key {@code assets/{userId}/{assetId}.{ext}} (D13 — moving projects
 * never rewrites objects, {@code projectId} is DB metadata only).
 *
 * <p>Ownership is enforced on every read/mutation (cross-user → 404, N-1);
 * project ownership is validated through {@link ProjectService} (ADR-17).
 * MIME whitelist + per-asset size ceiling ({@code NOVA_MAX_UPLOAD_BYTES}, N-2).
 * Same-hash duplicate within (user, project) → 409 {@code ASSET_ALREADY_EXISTS}
 * (R-5 hint; import wizard skips duplicates for idempotency, A9).
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    public static final String KIND_IMAGE = "image";
    public static final String KIND_TEXT = "text";

    /** MIME whitelist (N-2/G) — images only for binary assets. */
    public static final Set<String> IMAGE_MIME_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif");

    /** Normalized source enum (PRD §8.3 / AssetSourceKind) — WIN-39 新增 'canvas'/'conversation'（ADR-36）。 */
    public static final Set<String> SOURCE_KINDS = Set.of(
            "text-to-image", "image-to-image", "agent", "reverse-prompt", "gif",
            "upload", "random", "prompt-gallery", "manual", "canvas", "conversation");

    public static final String UNCLASSIFIED = "__unclassified__";

    private final AssetRepository repository;
    private final ObjectStorageManager storageManager;
    private final ProjectService projectService;
    private final long maxUploadBytes;

    public AssetService(AssetRepository repository,
                        ObjectStorageManager storageManager,
                        ProjectService projectService,
                        @Value("${nova.storage.max-upload-bytes:20971520}") long maxUploadBytes) {
        this.repository = repository;
        this.storageManager = storageManager;
        this.projectService = projectService;
        this.maxUploadBytes = maxUploadBytes;
    }

    // ===== create =====

    public AssetRow createImage(UUID userId, String projectId, String name, List<String> tags,
                                String note, String sourceKind, String sourceLabel, String sourceRef,
                                String prompt, byte[] fileBytes, String mimeType,
                                Integer width, Integer height, Instant now) {
        return createImage(userId, projectId, name, tags, note, sourceKind, sourceLabel, sourceRef,
                prompt, fileBytes, mimeType, width, height, now, null);
    }

    /**
     * Creates an image asset from uploaded bytes. {@code projectId} null →
     * the user's default project (ADR-17 fallback); explicit ids are ownership
     * validated (404 on cross-user). Key: {@code assets/{userId}/{assetId}.{ext}}
     * (D13, server-generated — client filenames never enter the key, ADR-16).
     * {@code extra} 为 WIN-39 类型扩展 JSONB（目录描述/缩略图/子类型等，ADR-36）。
     */
    public AssetRow createImage(UUID userId, String projectId, String name, List<String> tags,
                                String note, String sourceKind, String sourceLabel, String sourceRef,
                                String prompt, byte[] fileBytes, String mimeType,
                                Integer width, Integer height, Instant now, String extra) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("文件内容不能为空");
        }
        if (fileBytes.length > maxUploadBytes) {
            throw new HttpErrorException(413, "PAYLOAD_TOO_LARGE",
                    "文件大小超过上限（" + (maxUploadBytes / 1024 / 1024) + "MB）", null);
        }
        if (mimeType == null || !IMAGE_MIME_TYPES.contains(mimeType.toLowerCase())) {
            throw new IllegalArgumentException("仅支持图片文件（png/jpeg/webp/gif）");
        }
        String resolvedProject = resolveProjectId(userId, projectId);
        String kind = normalizeSourceKind(sourceKind);
        String hash = sha256(fileBytes);
        Optional<AssetRepository.AssetRow> duplicate = repository.findDuplicate(userId, resolvedProject, hash);
        if (duplicate.isPresent()) {
            throw new HttpErrorException(409, "ASSET_ALREADY_EXISTS",
                    "该项目中已存在相同图片", null);
        }

        String id = UUID.randomUUID().toString();
        String ext = extensionFor(mimeType);
        String storageKey = "assets/" + userId + "/" + id + "." + ext;

        storageManager.active().put(storageKey, fileBytes, mimeType);
        try {
            AssetEntity entity = new AssetEntity();
            entity.setId(id);
            entity.setUserId(userId.toString());
            entity.setProjectId(resolvedProject);
            entity.setKind(KIND_IMAGE);
            entity.setName(blankToNull(name) != null ? name.trim() : defaultImageName(now));
            entity.setMimeType(mimeType.toLowerCase());
            entity.setSizeBytes((long) fileBytes.length);
            entity.setWidth(width);
            entity.setHeight(height);
            entity.setTags(toJsonArray(tags));
            entity.setNote(blankToNull(note));
            entity.setSourceKind(kind);
            entity.setSourceLabel(blankToNull(sourceLabel) != null ? sourceLabel.trim() : null);
            entity.setSourceRef(blankToNull(sourceRef));
            entity.setPrompt(blankToNull(prompt));
            entity.setStorageKey(storageKey);
            entity.setHash(hash);
            entity.setExtra(validExtra(extra));
            entity.setRefCount(0L);
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            entity.setLastUsedAt(now);
            repository.insert(entity);
        } catch (RuntimeException e) {
            // object already written; roll it back so no orphan stays behind
            try {
                storageManager.active().delete(storageKey);
            } catch (RuntimeException ignored) {
                // best-effort
            }
            throw e;
        }
        log.info("[asset] 创建图片素材: user={}, asset={}, project={}, source={}",
                userId, id, resolvedProject, kind);
        return repository.findByIdAndOwner(id, userId).orElseThrow();
    }

    /** Creates a text asset (新建提示词 / manual / migration import). */
    public AssetRow createText(UUID userId, String projectId, String content, String name,
                               List<String> tags, String note, String sourceKind, String sourceLabel,
                               String sourceRef, Instant now) {
        return createText(userId, projectId, content, name, tags, note, sourceKind, sourceLabel,
                sourceRef, now, null);
    }

    /** Creates a text asset (新建提示词 / manual / migration import). {@code extra} WIN-39 扩展。 */
    public AssetRow createText(UUID userId, String projectId, String content, String name,
                               List<String> tags, String note, String sourceKind, String sourceLabel,
                               String sourceRef, Instant now, String extra) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("内容不能为空");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > 10 * 1024) {
            throw new IllegalArgumentException("内容长度不能超过 10KB");
        }
        String resolvedProject = resolveProjectId(userId, projectId);
        String kind = normalizeSourceKind(sourceKind);
        String hash = "text-" + sha256(content.trim().getBytes(StandardCharsets.UTF_8));
        Optional<AssetRepository.AssetRow> duplicate = repository.findDuplicate(userId, resolvedProject, hash);
        if (duplicate.isPresent()) {
            throw new HttpErrorException(409, "ASSET_ALREADY_EXISTS",
                    "该项目中已存在相同内容", null);
        }

        String id = UUID.randomUUID().toString();
        AssetEntity entity = new AssetEntity();
        entity.setId(id);
        entity.setUserId(userId.toString());
        entity.setProjectId(resolvedProject);
        entity.setKind(KIND_TEXT);
        entity.setName(blankToNull(name) != null ? name.trim() : null);
        entity.setSizeBytes((long) content.getBytes(StandardCharsets.UTF_8).length);
        entity.setTags(toJsonArray(tags));
        entity.setNote(blankToNull(note));
        entity.setSourceKind(kind);
        entity.setSourceLabel(blankToNull(sourceLabel) != null ? sourceLabel.trim() : null);
        entity.setSourceRef(blankToNull(sourceRef));
        entity.setPrompt(content.trim());           // text content lives in prompt (新建提示词)
        entity.setStorageKey(null);                 // text assets have no object (E.2)
        entity.setHash(hash);
        entity.setExtra(validExtra(extra));
        entity.setRefCount(0L);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setLastUsedAt(now);
        repository.insert(entity);
        return repository.findByIdAndOwner(id, userId).orElseThrow();
    }

    // ===== read =====

    public AssetRepository.AssetPage list(UUID userId, String projectId, String source, String q,
                          String tag, String sort, int page, int size,
                          boolean excludeWorking, boolean includeDeleted) {
        int safeSize = Math.min(Math.max(size <= 0 ? 48 : size, 1), 200);
        int safePage = Math.max(page <= 0 ? 1 : page, 1);
        return repository.search(userId, projectId, source, q, tag, sort, safePage, safeSize,
                excludeWorking, includeDeleted);
    }

    /** WIN-22 兼容签名（不排除工作态、不含软删）。 */
    public AssetRepository.AssetPage list(UUID userId, String projectId, String source, String q,
                          String tag, String sort, int page, int size) {
        return list(userId, projectId, source, q, tag, sort, page, size, false, false);
    }

    public AssetRow get(UUID userId, String id) {
        return repository.findByIdAndOwner(id, userId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "素材不存在"));
    }

    /** Object bytes + content type for /file and /download (owner-scoped). */
    public Optional<StoredAssetFile> getFile(UUID userId, String id) {
        AssetRepository.AssetRow row = get(userId, id);
        if (row.kind().equals(KIND_TEXT) || row.storageKey() == null) {
            return Optional.empty();
        }
        return storageManager.active().get(row.storageKey())
                .map(bytes -> new StoredAssetFile(bytes, row.mimeType() != null ? row.mimeType() : "application/octet-stream",
                        fileName(row)));
    }

    public record StoredAssetFile(byte[] data, String contentType, String fileName) {
    }

    // ===== update (edit + move, F-20) =====

    public AssetRepository.AssetRow update(UUID userId, String id, String name, List<String> tags,
                                           String note, String projectId) {
        AssetRepository.AssetRow row = get(userId, id);
        String nextName = name != null ? (name.trim().isEmpty() ? null : name.trim()) : row.name();
        String nextNote = note != null ? (note.isBlank() ? null : note.trim()) : row.note();
        String nextProject = row.projectId();
        if (projectId != null && !projectId.isBlank()) {
            nextProject = resolveProjectId(userId, projectId);   // move (D13: key unchanged)
        }
        AssetEntity patch = new AssetEntity();
        patch.setId(id);
        patch.setName(nextName);
        patch.setTags(tags != null ? toJsonArray(tags) : row.tags());
        patch.setNote(nextNote);
        patch.setProjectId(nextProject);
        patch.setUpdatedAt(Instant.now());
        repository.update(patch);
        return get(userId, id);
    }

    // ===== delete =====

    /**
     * 删除素材（WIN-39 语义：一律软删进回收站，ADR-42）；引用中的素材（ref_count&gt;0）
     * 即使后续硬删路径也必须降级软删（A7 引用保护）。硬删由每日清理任务按保留期执行。
     */
    public void delete(UUID userId, String id) {
        AssetRepository.AssetRow row = get(userId, id);
        if (row.deletedAt() != null) {
            return; // 已在回收站，幂等
        }
        if (row.refCount() != null && row.refCount() > 0) {
            log.info("[asset] 引用保护降级软删: user={}, asset={}, refCount={}", userId, id, row.refCount());
        }
        repository.softDelete(id, Instant.now());
        log.info("[asset] 软删素材(回收站): user={}, asset={}, refCount={}", userId, id, row.refCount());
    }

    /** 恢复回收站素材（ADR-42 restore）。 */
    public void restore(UUID userId, String id) {
        AssetRepository.AssetRow row = get(userId, id);
        if (row.deletedAt() == null) {
            return; // 未删除，幂等
        }
        repository.restore(id);
        log.info("[asset] 恢复素材: user={}, asset={}", userId, id);
    }

    /** 回收站列表（includeDeleted=true，ADR-42）。 */
    public AssetRepository.AssetPage listDeleted(UUID userId, String projectId, int page, int size) {
        int safeSize = Math.min(Math.max(size <= 0 ? 48 : size, 1), 200);
        int safePage = Math.max(page <= 0 ? 1 : page, 1);
        return repository.search(userId, projectId, null, null, null, "newest", safePage, safeSize,
                false, true);
    }

    /** 引用计数调整（画布/会话引用变更时调用，A7 尽力而为）。 */
    public void adjustRefCounts(UUID userId, List<String> assetIds, int delta) {
        repository.adjustRefCounts(userId, assetIds, delta);
    }

    /** WIN-39: 合并更新素材 extra JSONB（会话图片目录 description 等，ADR-36）。 */
    public AssetRepository.AssetRow updateExtra(UUID userId, String id, Map<String, Object> extraPatch) {
        AssetRepository.AssetRow row = get(userId, id);
        Map<String, Object> merged = new LinkedHashMap<>(parseExtra(row.extra()));
        if (extraPatch != null) {
            merged.putAll(extraPatch);
        }
        AssetEntity patch = new AssetEntity();
        patch.setId(id);
        patch.setExtra(toJsonObject(merged));
        patch.setUpdatedAt(Instant.now());
        repository.update(patch);
        return get(userId, id);
    }

    /** WIN-39: 会话图片目录 = assets WHERE source_kind='conversation' AND source_ref=<conversation_id>（ADR-36）。 */
    public List<AssetRepository.AssetRow> listConversationImages(UUID userId, String conversationId) {
        return repository.findBySourceRef(userId, "conversation", conversationId);
    }

    /**
     * 批量删除（WIN-39 语义：一律软删进回收站，ADR-42；对象保留至清理任务硬删）。
     */
    public int batchDelete(UUID userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> bounded = ids.stream().distinct().limit(200).toList();
        List<AssetRepository.AssetRow> rows = new ArrayList<>();
        for (String id : bounded) {
            repository.findByIdAndOwner(id, userId).ifPresent(rows::add);
        }
        Instant now = Instant.now();
        for (AssetRepository.AssetRow row : rows) {
            if (row.deletedAt() == null) {
                repository.softDelete(row.id(), now);
            }
        }
        log.info("[asset] 批量软删(回收站): user={}, count={}", userId, rows.size());
        return rows.size();
    }

    public int batchMove(UUID userId, List<String> ids, String projectId) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        String targetProject = resolveProjectId(userId, projectId);
        List<String> bounded = ids.stream().distinct().limit(200).toList();
        int moved = 0;
        for (String id : bounded) {
            if (!repository.findByIdAndOwner(id, userId).isPresent()) {
                continue;
            }
            AssetEntity patch = new AssetEntity();
            patch.setId(id);
            patch.setProjectId(targetProject);
            patch.setUpdatedAt(Instant.now());
            repository.update(patch);
            moved++;
        }
        log.info("[asset] 批量移动: user={}, count={}, targetProject={}", userId, moved, targetProject);
        return moved;
    }

    // ===== project cascade hooks (used by ProjectService.delete force) =====

    public long countByProject(UUID userId, String projectId) {
        return repository.countByProject(userId, projectId);
    }

    public Instant lastActivityByProject(UUID userId, String projectId) {
        return repository.lastActivityByProject(userId, projectId);
    }

    /** Cascade delete of a project's assets (DB + objects) — ADR-18 force path. */
    public void deleteByProject(UUID userId, String projectId) {
        List<AssetRepository.AssetRow> rows = repository.findByProject(userId, projectId);
        repository.deleteByIdsAndOwner(userId, rows.stream().map(AssetRepository.AssetRow::id).toList());
        for (AssetRepository.AssetRow row : rows) {
            if (row.storageKey() != null) {
                deleteObjectWithRetry(row.storageKey());
            }
        }
        log.info("[asset] 项目级联删除素材: user={}, project={}, count={}", userId, projectId, rows.size());
    }

    // ===== helpers =====

    /** Resolves a project id: default project fallback (null) + ownership 404. */
    private String resolveProjectId(UUID userId, String projectId) {
        if (projectId == null || projectId.isBlank() || UNCLASSIFIED.equals(projectId)) {
            return projectService.defaultProjectId(userId);
        }
        if (!projectService.owns(userId, projectId)) {
            throw new HttpErrorException(404, "NOT_FOUND", "项目不存在");
        }
        return projectId;
    }

    private String normalizeSourceKind(String sourceKind) {
        if (sourceKind == null || !SOURCE_KINDS.contains(sourceKind)) {
            throw new IllegalArgumentException("来源分类无效");
        }
        return sourceKind;
    }

    private static String extensionFor(String mimeType) {
        String normalized = mimeType.toLowerCase();
        if (normalized.contains("jpeg")) {
            return "jpg";
        }
        if (normalized.contains("webp")) {
            return "webp";
        }
        if (normalized.contains("gif")) {
            return "gif";
        }
        return "png";
    }

    private static String defaultImageName(Instant now) {
        return "素材-" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneId.systemDefault()).format(now);
    }

    private void deleteObjectWithRetry(String key) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (storageManager.active().delete(key)) {
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("[asset] 对象删除失败(第{}次): {}", attempt + 1, key, e);
            }
        }
        log.warn("[asset] 对象删除最终失败（孤儿对象，P1 兜底扫描）: {}", key);
    }

    /**
     * WIN-39 (T7 幂等修复, S2): 迁移幂等上传 —— 同 hash 素材已存在（同用户任意项目）时直接返回
     * 已有素材，否则创建。避免「失败重试 / 会话内同图」时 409 导致引用悬挂（FR-7.2 可重试 + 引用改写）。
     */
    public AssetRow createImageIdempotent(UUID userId, String projectId, String name, List<String> tags,
                                          String note, String sourceKind, String sourceLabel, String sourceRef,
                                          String prompt, byte[] fileBytes, String mimeType,
                                          Integer width, Integer height, Instant now, String extra) {
        String hash = sha256(fileBytes);
        Optional<AssetRepository.AssetRow> existing = repository.findByHash(userId, hash);
        if (existing.isPresent()) {
            log.info("[asset] 幂等上传命中已有素材: user={}, asset={}, hash={}",
                    userId, existing.get().id(), hash.substring(0, Math.min(12, hash.length())));
            return existing.get();
        }
        return createImage(userId, projectId, name, tags, note, sourceKind, sourceLabel, sourceRef,
                prompt, fileBytes, mimeType, width, height, now, extra);
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("哈希计算失败", e);
        }
    }

    private static String toJsonArray(List<String> values) {
        if (values == null) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            String tag = value == null ? "" : value.trim();
            if (tag.isEmpty()) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            sb.append('"').append(tag.replace("\"", "\\\"")).append('"');
            first = false;
        }
        sb.append(']');
        return sb.toString();
    }

    private static String fileName(AssetRepository.AssetRow row) {
        String base = row.name() != null && !row.name().isBlank() ? row.name() : row.id();
        String ext = row.mimeType() != null ? extensionFor(row.mimeType()) : "png";
        return base + "." + ext;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** extra JSON 校验/归一：非法输入降级为 '{}'，不阻塞上传。 */
    private static String validExtra(String extra) {
        if (extra == null || extra.isBlank()) {
            return "{}";
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(extra);
            if (node != null && node.isObject()) {
                return node.toString();
            }
            return "{}";
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String toJsonObject(Map<String, Object> map) {
        try {
            return new tools.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Public JSON shape (storage_key deliberately omitted — internal detail, ADR-20). */
    public static Map<String, Object> toJson(AssetRepository.AssetRow row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.id());
        map.put("kind", row.kind());
        map.put("projectId", row.projectId());
        map.put("name", row.name());
        map.put("mimeType", row.mimeType());
        map.put("sizeBytes", row.sizeBytes());
        map.put("width", row.width());
        map.put("height", row.height());
        map.put("tags", parseTags(row.tags()));
        map.put("note", row.note());
        map.put("sourceKind", row.sourceKind());
        map.put("sourceLabel", row.sourceLabel());
        map.put("sourceRef", row.sourceRef());
        map.put("prompt", row.prompt());
        if (KIND_TEXT.equals(row.kind())) {
            map.put("content", row.prompt());   // text content surfaces as content
        }
        map.put("extra", parseExtra(row.extra()));
        map.put("deletedAt", row.deletedAt() == null ? null : row.deletedAt().toString());
        map.put("refCount", row.refCount() == null ? 0L : row.refCount());
        map.put("hash", row.hash());
        map.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        map.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        map.put("lastUsedAt", row.lastUsedAt() == null ? null : row.lastUsedAt().toString());
        return map;
    }

    /** extra JSONB → Map（失效时返回空 map，不阻塞读取）。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseExtra(String extra) {
        if (extra == null || extra.isBlank() || "{}".equals(extra)) {
            return Map.of();
        }
        try {
            var node = new tools.jackson.databind.ObjectMapper().readTree(extra);
            if (node != null && node.isObject()) {
                return new tools.jackson.databind.ObjectMapper()
                        .convertValue(node, Map.class);
            }
            return Map.of();
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static List<String> parseTags(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank() || "[]".equals(jsonArray)) {
            return List.of();
        }
        try {
            var arr = new tools.jackson.databind.ObjectMapper().readTree(jsonArray);
            List<String> tags = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                arr.forEach(node -> tags.add(node.asText()));
            }
            return tags;
        } catch (Exception e) {
            return List.of();
        }
    }
}
