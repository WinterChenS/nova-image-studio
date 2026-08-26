package com.nova.studio.web;

import com.nova.studio.asset.AssetRepository;
import com.nova.studio.asset.AssetService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.canvas.CanvasProjectRepository;
import com.nova.studio.canvas.CanvasService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-39 (WIN-40 T5) — 画布后端 API（ARCH Part G.2）：
 * projects CRUD + 整文档 PUT（version）+ 软删/恢复（回收站）+ 画布图片上传（assets）。
 * 每条路由 requireAuth；属主隔离 → 404（AC-10）；配额超限 → 409（AC-11）。
 */
@RestController
@RequestMapping("/api/nova/canvas")
public class CanvasController {

    private final CanvasService canvasService;

    public CanvasController(CanvasService canvasService) {
        this.canvasService = canvasService;
    }

    @GetMapping("/projects")
    public Map<String, Object> list(@RequestParam(defaultValue = "false") boolean includeDeleted,
                                    @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CanvasProjectRepository.CanvasRow row : canvasService.list(authUser.id(), includeDeleted)) {
            items.add(canvasService.toJson(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        return body;
    }

    @PostMapping("/projects")
    public ResponseEntity<Map<String, Object>> create(@RequestBody(required = false) JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        String title = body != null && body.hasNonNull("title") ? body.get("title").asText() : null;
        String clientId = body != null && body.hasNonNull("id") ? body.get("id").asText() : null;
        return ResponseEntity.status(201).body(canvasService.toJson(canvasService.create(authUser.id(), title, clientId)));
    }

    @GetMapping("/projects/{id}")
    public Map<String, Object> get(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return canvasService.toJson(canvasService.get(authUser.id(), id));
    }

    /** 整文档保存（防抖提交；body 含 nodes/connections/viewport/backgroundMode；version 自增）。 */
    @PutMapping("/projects/{id}")
    public Map<String, Object> saveDocument(@PathVariable String id, @RequestBody JsonNode body,
                                            @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return canvasService.toJson(canvasService.saveDocument(authUser.id(), id, body));
    }

    @PatchMapping("/projects/{id}")
    public Map<String, Object> patch(@PathVariable String id, @RequestBody JsonNode body,
                                     @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return canvasService.toJson(canvasService.patch(authUser.id(), id, body));
    }

    @DeleteMapping("/projects/{id}")
    public Map<String, Object> softDelete(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        canvasService.softDelete(authUser.id(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/projects/{id}/restore")
    public Map<String, Object> restore(@PathVariable String id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        canvasService.restore(authUser.id(), id);
        return Map.of("ok", true);
    }

    /** T16：清空回收站（硬删全部软删项目）。 */
    @DeleteMapping("/projects/trash")
    public Map<String, Object> emptyTrash(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        int removed = canvasService.emptyTrash(authUser.id());
        return Map.of("ok", true, "removed", removed);
    }

    /** 画布图片上传（multipart → assets source_kind='canvas'，返回 assetId 供节点引用）。 */
    @PostMapping("/images")
    public ResponseEntity<Map<String, Object>> uploadImage(
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) String name,
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
        AssetRepository.AssetRow created = canvasService.uploadImage(
                authUser.id(), bytes, file.getContentType(), name, width, height);
        Map<String, Object> json = new LinkedHashMap<>(AssetService.toJson(created));
        json.put("assetId", created.id());
        return ResponseEntity.status(201).body(json);
    }
}
