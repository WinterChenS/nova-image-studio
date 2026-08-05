package com.nova.studio.web;

import com.nova.studio.auth.AuthFilter;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.settings.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Settings API (T2.1) — {@code /api/nova/settings}:
 * <ul>
 *   <li>{@code GET} → the user's whole settings package (flat dotted keys);</li>
 *   <li>{@code PUT} → whole-package upsert (allowlist-validated, idempotent);</li>
 *   <li>{@code POST /import} → legacy localStorage export import (T2.6).</li>
 * </ul>
 * All endpoints require login and are per-user isolated (T2.2).
 */
@RestController
@RequestMapping("/api/nova/settings")
public class SettingsController {

    private final SettingsService settingsService;

    public SettingsController(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public Map<String, Object> get(HttpServletRequest request) {
        AuthUser user = AuthFilter.require(request);
        return settingsService.getAll(user.id());
    }

    @PutMapping
    public Map<String, Object> put(@RequestBody JsonNode body, HttpServletRequest request) {
        AuthUser user = AuthFilter.require(request);
        settingsService.putAll(user.id(), body);
        return Map.of("ok", true);
    }

    @PostMapping("/import")
    public Map<String, Object> importLegacy(@RequestBody JsonNode body, HttpServletRequest request) {
        AuthUser user = AuthFilter.require(request);
        return settingsService.importLegacy(user.id(), body);
    }
}
