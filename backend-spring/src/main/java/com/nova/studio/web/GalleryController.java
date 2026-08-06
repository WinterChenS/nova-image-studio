package com.nova.studio.web;

import com.nova.studio.gallery.GalleryDataService;
import com.nova.studio.infra.RuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prompt gallery / blacklist / config endpoints (contract parity for the 12-item
 * checklist #12).
 *
 * <p>T3.1 (WIN-13): prompts/blacklist moved to the DB — {@code GalleryDataService}
 * serves them DB-first with legacy-file fallback (F-14, ARCH H5). The public
 * payloads keep the exact file-era shapes ({@code [{title, content, type}]} and
 * {@code {keywords: [...]}}) so the frontend stays untouched. {@code /config}
 * and {@code /prompt-gallery/verify} stay env-driven (ops-knob layer, A.5-Q4/H4).
 */
@RestController
@RequestMapping("/api/nova")
public class GalleryController {

    private static final Logger log = LoggerFactory.getLogger(GalleryController.class);
    private static final String PROMPT_GALLERY_PASSWORD_SALT = "nova-pg-2026";

    private final RuntimeEnv runtimeEnv;
    private final GalleryDataService galleryDataService;

    public GalleryController(RuntimeEnv runtimeEnv, GalleryDataService galleryDataService) {
        this.runtimeEnv = runtimeEnv;
        this.galleryDataService = galleryDataService;
    }

    @GetMapping("/prompts")
    public List<Map<String, Object>> prompts() {
        // Public shape parity with the legacy file: only title/content/type.
        return galleryDataService.prompts().stream()
                .map(row -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("title", row.title());
                    item.put("content", row.content());
                    item.put("type", row.type());
                    return item;
                })
                .toList();
    }

    @GetMapping("/blacklist")
    public Map<String, Object> blacklist() {
        return Map.of("keywords", galleryDataService.blacklistKeywords());
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        String rawMode = String.valueOf(runtimeEnv.getString("PROMPT_GALLERY_MODE", "2")).trim();
        String mode = List.of("1", "2", "3").contains(rawMode) ? rawMode : "2";
        String password = String.valueOf(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).trim();
        Map<String, Object> body = Map.of(
                "promptGalleryMode", mode,
                "promptGalleryPasswordEnabled", !password.isEmpty());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate")
                .header("Pragma", "no-cache")
                .header("Expires", "0")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @PostMapping("/prompt-gallery/verify")
    public Map<String, Object> verify(@RequestBody(required = false) JsonNode body) {
        String expected = String.valueOf(runtimeEnv.getString("PROMPT_GALLERY_PASSWORD", "")).trim();
        if (expected.isEmpty()) {
            return Map.of("ok", true);
        }
        String password = body != null && body.has("password") && body.get("password").isTextual()
                ? body.get("password").asText() : "";
        boolean ok = hashGalleryPassword(password).equals(hashGalleryPassword(expected));
        return Map.of("ok", ok);
    }

    private static String hashGalleryPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((PROMPT_GALLERY_PASSWORD_SALT + String.valueOf(password)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
