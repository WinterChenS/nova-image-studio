package com.nova.studio.web;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.migration.MigrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WIN-39 (WIN-40 T7) — 迁移框架 API（ARCH Part G.2）：
 * <ul>
 *   <li>{@code POST /api/nova/migration/{agent|canvas|reverse|gif}/import} — 按功能批量导入（幂等）；</li>
 *   <li>{@code POST /api/nova/migration/upload-image} — 图片字节分批上传（→ assets，返回 assetId）。</li>
 * </ul>
 * requireAuth + user 隔离（FR-7.1：登录后迁移，服务端按 user_id 归属）。
 */
@RestController
@RequestMapping("/api/nova/migration")
public class MigrationController {

    private final MigrationService migrationService;

    public MigrationController(MigrationService migrationService) {
        this.migrationService = migrationService;
    }

    @PostMapping("/agent/import")
    public Map<String, Object> importAgent(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return migrationService.importConversations(authUser.id(), body).toMap();
    }

    @PostMapping("/canvas/import")
    public Map<String, Object> importCanvas(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return migrationService.importCanvasProjects(authUser.id(), body).toMap();
    }

    @PostMapping("/reverse/import")
    public Map<String, Object> importReverse(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return migrationService.importHistories(authUser.id(), "reverse", body).toMap();
    }

    @PostMapping("/gif/import")
    public Map<String, Object> importGif(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return migrationService.importHistories(authUser.id(), "gif", body).toMap();
    }

    @PostMapping("/upload-image")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String sourceKind,
            @RequestParam(required = false) String sourceRef,
            @RequestParam(required = false) String name,
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
        AssetRepository.AssetRow created = migrationService.uploadImage(
                authUser.id(), bytes, file.getContentType(), sourceKind, sourceRef, name);
        Map<String, Object> json = new LinkedHashMap<>(AssetService.toJson(created));
        json.put("assetId", created.id());
        return ResponseEntity.status(201).body(json);
    }
}
