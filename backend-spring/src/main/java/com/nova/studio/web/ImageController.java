package com.nova.studio.web;

import com.nova.studio.storage.ImageStorageService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.Map;

/**
 * Image hosting (T1.4) — {@code GET /api/nova/images/{taskId}/{index} }.
 * Port of the Node handler: taskId validated against {@code [a-zA-Z0-9-]},
 * common {@code {taskId}-{index}-0.{ext}} candidates tried first (no directory
 * scan), prefix scan fallback for multi-subimage tasks, {@code Cache-Control:
 * private, max-age=3600} and content type by extension.
 */
@RestController
@RequestMapping("/api/nova/images")
public class ImageController {

    private static final java.util.regex.Pattern TASK_ID_PATTERN =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9-]+$");

    private final ImageStorageService imageStorageService;

    public ImageController(ImageStorageService imageStorageService) {
        this.imageStorageService = imageStorageService;
    }

    @GetMapping("/{taskId}/{index}")
    public ResponseEntity<?> getImage(@PathVariable String taskId, @PathVariable int index) {
        if (!TASK_ID_PATTERN.matcher(taskId).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid taskId"));
        }
        Path file = imageStorageService.resolveItemImage(taskId, index);
        if (file == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not Found"));
        }
        Resource resource = new FileSystemResource(file.toFile());
        String contentType = contentTypeFor(file);
        // exact header string to match the Node backend byte-for-byte
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(resource);
    }

    /** Node getContentType — extension-based content type map. */
    public static String contentTypeFor(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (name.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (name.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (name.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        if (name.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (name.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (name.endsWith(".txt")) {
            return "text/plain; charset=utf-8";
        }
        return "application/octet-stream";
    }
}
