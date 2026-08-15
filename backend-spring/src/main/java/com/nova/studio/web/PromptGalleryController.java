package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.gallery.GalleryCategory;
import com.nova.studio.gallery.PromptGalleryItemEntity;
import com.nova.studio.gallery.PromptGalleryItemRepository;
import com.nova.studio.gallery.PromptGallerySyncService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-42 (T15, ADR-40) — 提示广场读库 API（前端主入口，不再运行时 fetch 远程源）：
 * <ul>
 *   <li>{@code GET /api/nova/prompt-gallery/items} — 读库列表（source/category/q/tag 过滤 + 分页，AC-9/T16 服务端搜索）；</li>
 *   <li>{@code GET /api/nova/prompt-gallery/items/{id}} — 详情；</li>
 *   <li>{@code GET /api/nova/prompt-gallery/categories} — 分类列表（分类栏）；</li>
 *   <li>{@code GET /api/nova/admin/prompt-gallery/sync/status} — 同步状态（AC-12）；</li>
 *   <li>{@code POST /api/nova/admin/prompt-gallery/sync} — 手动同步（限频，A5）。</li>
 * </ul>
 * 读库接口公开（广场为全局数据）；管理端点 requireAdmin + @PreAuthorize。
 */
@RestController
@RequestMapping("/api/nova/prompt-gallery")
public class PromptGalleryController {

    private static final Logger log = LoggerFactory.getLogger(PromptGalleryController.class);

    private final PromptGalleryItemRepository repository;
    private final PromptGallerySyncService syncService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public PromptGalleryController(PromptGalleryItemRepository repository,
                                   PromptGallerySyncService syncService,
                                   SettingsService settingsService,
                                   ObjectMapper objectMapper) {
        this.repository = repository;
        this.syncService = syncService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    // ===== 读库 API（公开）=====

    /** 读库列表：source/category 精确 + q 模糊 + tag 标签，分页（synced_at DESC）。 */
    @GetMapping("/items")
    public Map<String, Object> items(@RequestParam(required = false) String source,
                                     @RequestParam(required = false) String category,
                                     @RequestParam(required = false) String q,
                                     @RequestParam(required = false) String tag,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int limit) {
        PromptGalleryItemRepository.GalleryPage galleryPage =
                repository.search(source, category, q, tag, page, limit);
        Map<String, Object> body = new LinkedHashMap<>();
        ArrayNode items = objectMapper.createArrayNode();
        for (PromptGalleryItemEntity entity : galleryPage.items()) {
            items.add(objectMapper.valueToTree(toJson(entity)));
        }
        body.put("items", items);
        body.put("total", galleryPage.total());
        body.put("page", galleryPage.page());
        body.put("pageSize", galleryPage.pageSize());
        body.put("categories", repository.distinctCategories());
        body.put("sources", repository.distinctSources());
        return body;
    }

    @GetMapping("/items/{id}")
    public Map<String, Object> item(@PathVariable String id) {
        PromptGalleryItemEntity entity = repository.findById(id)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "提示词条目不存在"));
        return toJson(entity);
    }

    /** 分类列表（含「全部」前置，前端分类栏默认值）。 */
    @GetMapping("/categories")
    public Map<String, Object> categories() {
        List<String> list = repository.distinctCategories();
        if (!list.contains(GalleryCategory.CATEGORY_ALL)) {
            list = new java.util.ArrayList<>(list);
            list.add(0, GalleryCategory.CATEGORY_ALL);
        }
        return Map.of("categories", list);
    }

    // ===== 管理端点（requireAdmin + @PreAuthorize）=====

    /** 手动同步（限频 gallery.syncManualCooldownMinutes，A5）。 */
    @PreAuthorize("hasAuthority('PERM_admin.console.view')")
    @PostMapping("/sync")
    public Map<String, Object> manualSync(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        int cooldown = settingsService.getInt(authUser.id(),
                SettingsService.KEY_GALLERY_SYNC_MANUAL_COOLDOWN_MINUTES,
                SettingsService.DEFAULT_GALLERY_SYNC_MANUAL_COOLDOWN_MINUTES);
        PromptGallerySyncService.SyncResult result = syncService.manualSync(cooldown);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status());
        body.put("totalUpserted", result.totalUpserted());
        body.put("sources", result.sources().stream()
                .map(s -> Map.of("source", s.source(), "status", s.status(),
                        "upserted", s.upserted(),
                        "error", s.error() == null ? "" : s.error()))
                .toList());
        return body;
    }

    /** 同步状态（最近运行/各源结果/失败，AC-12）。 */
    @PreAuthorize("hasAuthority('PERM_admin.console.view')")
    @GetMapping("/sync/status")
    public Map<String, Object> syncStatus(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        try {
            ObjectNode node = syncService.status();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.convertValue(node, Map.class);
            return map == null ? new LinkedHashMap<>() : map;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    // ===== JSON shape =====

    private Map<String, Object> toJson(PromptGalleryItemEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("uniqueKey", entity.getId());
        map.put("source", entity.getSource());
        map.put("sourceUrl", entity.getSourceUrl());
        map.put("title", entity.getTitle());
        map.put("content", entity.getContent());
        map.put("images", parseJsonArray(entity.getImages()));
        map.put("tags", parseJsonArray(entity.getTags()));
        map.put("category", entity.getCategory());
        map.put("contributor", entity.getContributor());
        map.put("notes", entity.getNotes());
        map.put("syncedAt", entity.getSyncedAt() == null ? null : entity.getSyncedAt().toString());
        return map;
    }

    private List<String> parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            tools.jackson.databind.JsonNode node = objectMapper.readTree(raw);
            List<String> result = new java.util.ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(item -> {
                    if (item.isTextual()) {
                        result.add(item.asText());
                    }
                });
            }
            return result;
        } catch (Exception e) {
            return List.of();
        }
    }
}
