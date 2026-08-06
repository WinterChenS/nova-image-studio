package com.nova.studio.web;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WIN-22 (D.2) — asset API:
 * <ul>
 *   <li>{@code GET /api/nova/assets} — paginated, filtered list;</li>
 *   <li>{@code POST /api/nova/assets} — multipart image upload or JSON text asset;</li>
 *   <li>{@code GET /api/nova/assets/{id}} — metadata; {@code /file} — object bytes;
 *       {@code /download} — attachment;</li>
 *   <li>{@code PATCH /api/nova/assets/{id}} — edit + move (D13: key unchanged);</li>
 *   <li>{@code DELETE /api/nova/assets/{id}} + {@code batch-delete}/{@code batch-move}.</li>
 * </ul>
 * Every route requires login; ownership → 404 (N-1). List responses omit
 * {@code storage_key} (internal detail, ADR-20).
 */
@RestController
@RequestMapping("/api/nova/assets")
public class AssetController {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String projectId,
                                    @RequestParam(required = false) String source,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) String tag,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "48") int size,
                                    @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        AssetRepository.AssetPage pageResult = assetService.list(
                authUser.id(), projectId, source, q, tag, sort, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (AssetRepository.AssetRow row : pageResult.items()) {
            items.add(AssetService.toJson(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", pageResult.total());
        body.put("page", Math.max(page, 1));
        body.put("size", Math.min(Math.max(size <= 0 ? 48 : size, 1), 200));
        return body;
    }

    /**
     * Multipart image upload (B-2 修复：与 JSON 拆分为两个 handler，避免
     * {@code @RequestBody} 对 multipart Content-Type 触发 HttpMediaTypeNotSupportedException；
     * 不再声明 consumes 精确匹配 —— Boot 4.1 会把 multipart 归一为带 charset=UTF-8）。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createMultipart(@RequestParam(required = false) MultipartFile file,
                                                               @RequestParam(required = false) String projectId,
                                                               @RequestParam(required = false) String name,
                                                               @RequestParam(required = false) String tags,
                                                               @RequestParam(required = false) String note,
                                                               @RequestParam(required = false) String sourceKind,
                                                               @RequestParam(required = false) String sourceLabel,
                                                               @RequestParam(required = false) String sourceRef,
                                                               @RequestParam(required = false) String prompt,
                                                               @RequestParam(required = false) Integer width,
                                                               @RequestParam(required = false) Integer height,
                                                               @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请提供图片文件");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("文件读取失败");
        }
        Map<String, Object> created = AssetService.toJson(assetService.createImage(
                authUser.id(), projectId, name, splitTags(tags), note,
                sourceKind, sourceLabel, sourceRef, prompt,
                bytes, file.getContentType(), width, height, Instant.now()));
        return ResponseEntity.status(201).body(created);
    }

    /** JSON text asset (新建提示词 / 迁移导入). */
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<Map<String, Object>> createText(@RequestBody JsonNode jsonBody,
                                                          @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        if (jsonBody == null || !jsonBody.isObject() || !jsonBody.hasNonNull("content")) {
            throw new IllegalArgumentException("请提供文本内容");
        }
        Map<String, Object> created = AssetService.toJson(assetService.createText(
                authUser.id(),
                jsonBody.hasNonNull("projectId") ? jsonBody.get("projectId").asText() : null,
                jsonBody.get("content").asText(),
                jsonBody.hasNonNull("name") ? jsonBody.get("name").asText() : null,
                jsonArray(jsonBody.get("tags")),
                jsonBody.hasNonNull("note") ? jsonBody.get("note").asText() : null,
                jsonBody.hasNonNull("sourceKind") ? jsonBody.get("sourceKind").asText() : null,
                jsonBody.hasNonNull("sourceLabel") ? jsonBody.get("sourceLabel").asText() : null,
                jsonBody.hasNonNull("sourceRef") ? jsonBody.get("sourceRef").asText() : null,
                Instant.now()));
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return AssetService.toJson(assetService.get(authUser.id(), id));
    }

    @GetMapping("/{id}/file")
    public ResponseEntity<?> file(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        Optional<AssetService.StoredAssetFile> stored = assetService.getFile(authUser.id(), id);
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AssetService.StoredAssetFile assetFile = stored.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(assetFile.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(assetFile.data());
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        Optional<AssetService.StoredAssetFile> stored = assetService.getFile(authUser.id(), id);
        if (stored.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        AssetService.StoredAssetFile assetFile = stored.get();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(assetFile.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + assetFile.fileName().replace("\"", "") + "\"")
                .body(assetFile.data());
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody JsonNode body,
                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        String name = body.hasNonNull("name") ? body.get("name").asText() : null;
        String note = body.hasNonNull("note") ? body.get("note").asText() : null;
        String projectId = body.hasNonNull("projectId") ? body.get("projectId").asText() : null;
        List<String> tags = body.hasNonNull("tags") ? jsonArray(body.get("tags")) : null;
        return AssetService.toJson(assetService.update(authUser.id(), id, name, tags, note, projectId));
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        assetService.delete(authUser.id(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/batch-delete")
    public Map<String, Object> batchDelete(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        List<String> ids = jsonArray(body.get("ids"));
        int deleted = assetService.batchDelete(authUser.id(), ids);
        return Map.of("deleted", deleted);
    }

    @PostMapping("/batch-move")
    public Map<String, Object> batchMove(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        List<String> ids = jsonArray(body.get("ids"));
        String projectId = body.hasNonNull("projectId") ? body.get("projectId").asText() : null;
        int moved = assetService.batchMove(authUser.id(), ids, projectId);
        return Map.of("moved", moved);
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags.split("[,，\\s、]+")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static List<String> jsonArray(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isTextual()) {
                    result.add(item.asText());
                }
            });
        }
        return result;
    }
}
