package com.nova.studio.web;

import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * T2 (WIN-28, A2) — {@code GET /api/nova/models} now serves the global model
 * catalog (read-only, {@code enabled} + {@code available}). The per-user model
 * CRUD (POST/PUT/DELETE) is <b>下线</b> (Q1 直接移除 — the old {@code models}
 * table is logically frozen; P2 physical cleanup). Frontend edit UI removal is
 * M2 T19.
 */
@RestController
@RequestMapping("/api/nova/models")
public class ModelsController {

    private final CatalogModelService catalogModelService;

    public ModelsController(CatalogModelService catalogModelService) {
        this.catalogModelService = catalogModelService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAuth(authUser);
        return catalogModelService.listPublicCatalog();
    }
}
