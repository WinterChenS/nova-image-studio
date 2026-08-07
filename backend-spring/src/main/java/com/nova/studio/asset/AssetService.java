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

    /** Normalized 9-value source enum (PRD §8.3 / AssetSourceKind). */
    public static final Set<String> SOURCE_KINDS = Set.of(
            "text-to-image", "image-to-image", "agent", "reverse-prompt", "gif",
            "upload", "random", "prompt-gallery", "manual");

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

    /**
     * Creates an image asset from uploaded bytes. {@code projectId} null →
     * the user's default project (ADR-17 fallback); explicit ids are ownership
     * validated (404 on cross-user). Key: {@code assets/{userId}/{assetId}.{ext}}
     * (D13, server-generated — client filenames never enter the key, ADR-16).
     */
    public AssetRow createImage(UUID userId, String projectId, String name, List<String> tags,
                                String note, String sourceKind, String sourceLabel, String sourceRef,
                                String prompt, byte[] fileBytes, String mimeType,
                                Integer width, Integer height, Instant now) {
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
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setLastUsedAt(now);
        repository.insert(entity);
        return repository.findByIdAndOwner(id, userId).orElseThrow();
    }

    // ===== read =====

    public AssetRepository.AssetPage list(UUID userId, String projectId, String source, String q,
                          String tag, String sort, int page, int size) {
        int safeSize = Math.min(Math.max(size <= 0 ? 48 : size, 1), 200);
        int safePage = Math.max(page <= 0 ? 1 : page, 1);
        return repository.search(userId, projectId, source, q, tag, sort, safePage, safeSize);
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

    /** Deletes DB row first, then the object (N-5/F-38) with one retry + warn log. */
    public void delete(UUID userId, String id) {
        AssetRepository.AssetRow row = get(userId, id);
        repository.delete(id);
        if (row.storageKey() != null) {
            deleteObjectWithRetry(row.storageKey());
        }
        log.info("[asset] 删除素材: user={}, asset={}", userId, id);
    }

    public int batchDelete(UUID userId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        List<String> bounded = ids.stream().distinct().limit(200).toList();
        List<AssetRepository.AssetRow> rows = new ArrayList<>();
        for (String id : bounded) {
            repository.findByIdAndOwner(id, userId).ifPresent(rows::add);
        }
        repository.deleteByIdsAndOwner(userId, rows.stream().map(AssetRepository.AssetRow::id).toList());
        int objectFailures = 0;
        for (AssetRepository.AssetRow row : rows) {
            if (row.storageKey() != null) {
                deleteObjectWithRetry(row.storageKey());
            }
        }
        log.info("[asset] 批量删除: user={}, count={}, objectFailures={}", userId, rows.size(), objectFailures);
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
        map.put("hash", row.hash());
        map.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        map.put("updatedAt", row.updatedAt() == null ? null : row.updatedAt().toString());
        map.put("lastUsedAt", row.lastUsedAt() == null ? null : row.lastUsedAt().toString());
        return map;
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
