package com.nova.studio.web;

import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T2 (WIN-28) — model catalog management API {@code /api/nova/admin/models}
 * (A1: admin-only; 普通用户 403 无入口 — M1 沿用 {@link AuthSupport#requireAdmin},
 * M2 T14 升级为权限码 {@code PERM_model.catalog.manage}).
 */
@RestController
@RequestMapping("/api/nova/admin/models")
public class AdminCatalogController {

    private final CatalogModelService catalogModelService;

    public AdminCatalogController(CatalogModelService catalogModelService) {
        this.catalogModelService = catalogModelService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return catalogModelService.listAdmin();
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(catalogModelService.create(admin.id(), body));
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody JsonNode body,
                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        return catalogModelService.update(admin.id(), id, body);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id, @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        catalogModelService.delete(admin.id(), id);
        return Map.of("ok", true);
    }
}
