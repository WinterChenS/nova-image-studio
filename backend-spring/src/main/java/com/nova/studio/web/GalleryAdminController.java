package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.gallery.GalleryDataService;
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
 * T3.1 — admin management API for the prompt gallery data (F-14 / ARCH H5:
 * 管理接口 admin 角色).
 *
 * <ul>
 *   <li>{@code GET/POST /api/nova/admin/prompts}, {@code PUT/DELETE
 *       /api/nova/admin/prompts/{id}}</li>
 *   <li>{@code GET/POST /api/nova/admin/blacklist},
 *       {@code DELETE /api/nova/admin/blacklist/{keyword}}</li>
 * </ul>
 *
 * <p>Admin role is enforced in {@code AuthSupport.requireAdmin} (403 for
 * non-admin, 401 for anonymous — the chain requires authentication on
 * {@code /api/nova/admin/**} first, see {@code SecurityConfig}).
 */
@RestController
@RequestMapping("/api/nova/admin")
public class GalleryAdminController {

    private final GalleryDataService galleryDataService;

    public GalleryAdminController(GalleryDataService galleryDataService) {
        this.galleryDataService = galleryDataService;
    }

    // ===== prompts =====

    @GetMapping("/prompts")
    public List<GalleryDataService.PromptRow> listPrompts(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return galleryDataService.listPrompts();
    }

    @PostMapping("/prompts")
    public ResponseEntity<GalleryDataService.PromptRow> createPrompt(@RequestBody JsonNode body,
                                                                     @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        GalleryDataService.PromptRow row = galleryDataService.createPrompt(
                text(body, "title"), text(body, "content"),
                intOrNull(body, "type"), boolOrNull(body, "enabled"), intOrNull(body, "sortOrder"));
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    @PutMapping("/prompts/{id}")
    public GalleryDataService.PromptRow updatePrompt(@PathVariable UUID id, @RequestBody JsonNode body,
                                                     @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return galleryDataService.updatePrompt(id,
                textOrNull(body, "title"), textOrNull(body, "content"),
                intOrNull(body, "type"), boolOrNull(body, "enabled"), intOrNull(body, "sortOrder"));
    }

    @DeleteMapping("/prompts/{id}")
    public Map<String, Object> deletePrompt(@PathVariable UUID id, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        galleryDataService.deletePrompt(id);
        return Map.of("ok", true);
    }

    // ===== blacklist =====

    @GetMapping("/blacklist")
    public List<GalleryDataService.KeywordRow> listKeywords(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return galleryDataService.listKeywords();
    }

    @PostMapping("/blacklist")
    public ResponseEntity<GalleryDataService.KeywordRow> addKeyword(@RequestBody JsonNode body,
                                                                    @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        GalleryDataService.KeywordRow row = galleryDataService.addKeyword(text(body, "keyword"));
        return ResponseEntity.status(HttpStatus.CREATED).body(row);
    }

    @DeleteMapping("/blacklist/{keyword}")
    public Map<String, Object> deleteKeyword(@PathVariable String keyword, @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        galleryDataService.deleteKeyword(keyword);
        return Map.of("ok", true);
    }

    // ===== payload helpers =====

    private static String text(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static String textOrNull(JsonNode body, String field) {
        String value = text(body, field);
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer intOrNull(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    private static Boolean boolOrNull(JsonNode body, String field) {
        JsonNode node = body == null ? null : body.get(field);
        return node != null && node.isBoolean() ? node.asBoolean() : null;
    }
}
