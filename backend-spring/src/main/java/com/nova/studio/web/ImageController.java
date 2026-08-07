package com.nova.studio.web;

import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.storage.ImageStorageService.StoredImage;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Image hosting (T1.4) — {@code GET /api/nova/images/{taskId}/{index} }.
 * Port of the Node handler: taskId validated against {@code [a-zA-Z0-9-]},
 * {@code Cache-Control: private, max-age=3600} and content type by extension.
 * WIN-22 (ADR-13): reads route through {@link ImageStorageService#resolveTaskImage}
 * — disk files or MinIO objects (contract unchanged, F-31/N-3).
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
        Optional<StoredImage> image = imageStorageService.resolveTaskImage(taskId, index);
        if (image.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not Found"));
        }
        StoredImage stored = image.get();
        // exact header string to match the Node backend byte-for-byte
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(stored.data());
    }

    /** Node getContentType — extension-based content type map (kept for compat). */
    public static String contentTypeFor(java.nio.file.Path file) {
        return ImageStorageService.contentTypeFor(file);
    }
}
