package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.gallery.PromptGallerySyncService;
import com.nova.studio.settings.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WIN-42 (T15, ADR-40) — 提示广场管理端点（/api/nova/admin/prompt-gallery/*，requireAdmin）：
 * <ul>
 *   <li>{@code POST /api/nova/admin/prompt-gallery/sync} — 手动同步（限频 gallery.syncManualCooldownMinutes，A5）；</li>
 *   <li>{@code GET /api/nova/admin/prompt-gallery/sync/status} — 同步状态（最近运行/各源结果/失败，AC-12）。</li>
 * </ul>
 */
@RestController
@PreAuthorize("hasAuthority('PERM_admin.console.view')")
@RequestMapping("/api/nova/admin/prompt-gallery")
public class PromptGalleryAdminController {

    private static final Logger log = LoggerFactory.getLogger(PromptGalleryAdminController.class);

    private final PromptGallerySyncService syncService;
    private final SettingsService settingsService;
    private final ObjectMapper objectMapper;

    public PromptGalleryAdminController(PromptGallerySyncService syncService,
                                        SettingsService settingsService,
                                        ObjectMapper objectMapper) {
        this.syncService = syncService;
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    /** 手动同步（限频 gallery.syncManualCooldownMinutes，A5）。 */
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
}
