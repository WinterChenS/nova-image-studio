package com.nova.studio.web;

import com.nova.studio.infra.RuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * Prompt gallery / blacklist / config endpoints (contract parity for the 12-item
 * checklist #12). M1 keeps the Node backend's file-backed behavior
 * ({@code prompts.json} / {@code blacklist.json} + env-driven mode); DB-ization
 * with the admin API is P1 (T3.1). Paths are configurable via
 * {@code NOVA_PROMPTS_PATH} / {@code NOVA_BLACKLIST_PATH}.
 */
@RestController
@RequestMapping("/api/nova")
public class GalleryController {

    private static final Logger log = LoggerFactory.getLogger(GalleryController.class);
    private static final String PROMPT_GALLERY_PASSWORD_SALT = "nova-pg-2026";

    private final RuntimeEnv runtimeEnv;
    private final Path promptsPath;
    private final Path blacklistPath;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public GalleryController(RuntimeEnv runtimeEnv,
                             @Value("${nova.storage.prompts-path:../backend/prompts.json}") String promptsPath,
                             @Value("${nova.storage.blacklist-path:../backend/blacklist.json}") String blacklistPath,
                             tools.jackson.databind.ObjectMapper objectMapper) {
        this.runtimeEnv = runtimeEnv;
        this.promptsPath = Path.of(promptsPath);
        this.blacklistPath = Path.of(blacklistPath);
        this.objectMapper = objectMapper;
    }

    @GetMapping("/prompts")
    public List<?> prompts() {
        try {
            if (!Files.exists(promptsPath)) {
                return List.of();
            }
            String raw = Files.readString(promptsPath, StandardCharsets.UTF_8);
            JsonNode data = objectMapper.readTree(raw);
            return objectMapper.convertValue(data.isArray() ? data : objectMapper.createArrayNode(), List.class);
        } catch (Exception e) {
            log.warn("[gallery] prompts 读取失败: {}", e.getMessage());
            return List.of();
        }
    }

    @GetMapping("/blacklist")
    public Map<String, Object> blacklist() {
        try {
            if (!Files.exists(blacklistPath)) {
                return Map.of("keywords", List.of());
            }
            String raw = Files.readString(blacklistPath, StandardCharsets.UTF_8);
            JsonNode data = objectMapper.readTree(raw);
            JsonNode keywords = data != null && data.has("keywords") && data.get("keywords").isArray()
                    ? data.get("keywords") : objectMapper.createArrayNode();
            return Map.of("keywords", objectMapper.convertValue(keywords, List.class));
        } catch (Exception e) {
            log.warn("[gallery] blacklist 读取失败: {}", e.getMessage());
            return Map.of("keywords", List.of());
        }
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
            byte[] hash = digest.digest((PROMPT_GALLERY_PASSWORD_SALT + String.valueOf(password)).getBytes(StandardCharsets.UTF_8));
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
