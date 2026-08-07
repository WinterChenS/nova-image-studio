package com.nova.studio.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Task-image facade (T1.4 + WIN-22 ADR-13). Keeps the Node-backend contract
 * ({@code {taskId}-{itemIndex}-{subIndex}.{ext}} flat naming on disk,
 * {@code GET /api/nova/images/{taskId}/{index}} unchanged) while routing the
 * actual bytes through {@link ObjectStorageManager}:
 * <ul>
 *   <li>disk mode → existing flat files under {@code NOVA_IMAGE_DIR};</li>
 *   <li>minio mode → keys {@code tasks/{taskId}/{itemIndex}-{subIndex}.{ext}}.</li>
 * </ul>
 * Degraded writes land on disk only (no dual-write, ARCH E.4).
 */
@Service
public class ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);

    private final ObjectStorageManager storageManager;

    public ImageStorageService(ObjectStorageManager storageManager) {
        this.storageManager = storageManager;
    }

    public Path imageDir() {
        return storageManager.active() instanceof DiskStorageService disk
                ? disk.rootDir()
                : Path.of("./data/nova-images");
    }

    public static String getImageExtension(String mimeType) {
        if (mimeType != null && (mimeType.contains("jpeg") || mimeType.contains("jpg"))) {
            return "jpg";
        }
        if (mimeType != null && mimeType.contains("webp")) {
            return "webp";
        }
        return "png";
    }

    /** MinIO object key for a task image (mode-aware naming, F-31). */
    public static String taskImageKey(String taskId, int itemIndex, int subIndex, String ext) {
        return "tasks/" + taskId + "/" + itemIndex + "-" + subIndex + "." + ext;
    }

    /** Writes an image buffer (MinIO when active, else disk); returns the public http URL. */
    public String saveImageToDisk(String taskId, int itemIndex, int subIndex, byte[] imageBuffer, String mimeType) {
        String ext = getImageExtension(mimeType);
        if (storageManager.isMinioActive()) {
            String key = taskImageKey(taskId, itemIndex, subIndex, ext);
            storageManager.active().put(key, imageBuffer, mimeType);
            log.debug("[image-storage] saved {} (minio)", key);
            return "/api/nova/images/" + taskId + "/" + itemIndex;
        }
        String fileName = taskId + "-" + itemIndex + "-" + subIndex + "." + ext;
        Path filePath = imageDir().resolve(fileName);
        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, imageBuffer);
        } catch (IOException e) {
            throw new IllegalStateException("图片保存失败: " + e.getMessage(), e);
        }
        log.debug("[image-storage] saved {}", fileName);
        return "/api/nova/images/" + taskId + "/" + itemIndex;
    }

    /** Downloads a remote image URL and stores it (same routing as saveImageToDisk). */
    public String downloadUrlToDisk(String taskId, int itemIndex, int subIndex, String imageUrl) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(30)).build();
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(imageUrl))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .GET().build();
            java.net.http.HttpResponse<byte[]> response =
                    client.send(request, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("远程图片下载失败: " + response.statusCode());
            }
            String contentType = response.headers().firstValue("content-type").orElse("image/png");
            return saveImageToDisk(taskId, itemIndex, subIndex, response.body(), contentType);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("远程图片下载失败: " + e.getMessage(), e);
        }
    }

    public List<Path> getTaskImageFiles(String taskId) {
        Path dir = imageDir();
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        String prefix = taskId + "-";
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (p.getFileName().toString().startsWith(prefix)) {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    public boolean deleteImageFile(Path filePath) {
        try {
            return Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("[image-lifecycle] 删除文件失败: {}", filePath, e);
            return false;
        }
    }

    /** Deletes all images of a task — disk prefix scan or MinIO prefix list. */
    public int deleteTaskImageFiles(String taskId) {
        if (storageManager.isMinioActive()) {
            List<String> keys = storageManager.active().listByPrefix("tasks/" + taskId + "/");
            int deleted = 0;
            for (String key : keys) {
                if (storageManager.active().delete(key)) {
                    deleted++;
                }
            }
            log.info("[image-lifecycle] 任务图片清理完成(minio): taskId={}, total={}, deleted={}", taskId, keys.size(), deleted);
            return deleted;
        }
        List<Path> files = getTaskImageFiles(taskId);
        int deleted = 0;
        for (Path file : files) {
            if (deleteImageFile(file)) {
                deleted++;
            }
        }
        log.info("[image-lifecycle] 任务图片清理完成: taskId={}, total={}, deleted={}", taskId, files.size(), deleted);
        return deleted;
    }

    /**
     * Resolves the file for GET /api/nova/images/{taskId}/{index}: tries the
     * common {taskId}-{index}-0.{png,jpg,webp} candidates first (no directory
     * scan), then falls back to a prefix scan — port of the Node handler
     * (disk mode only; MinIO mode is handled by {@link #resolveTaskImage}).
     */
    public Path resolveItemImage(String taskId, int index) {
        Path dir = imageDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        for (String ext : new String[]{"png", "jpg", "webp"}) {
            Path candidate = dir.resolve(taskId + "-" + index + "-0." + ext);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        String prefix = taskId + "-" + index + "-";
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (p.getFileName().toString().startsWith(prefix)) {
                    files.add(p);
                }
            }
        } catch (IOException e) {
            return null;
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files.isEmpty() ? null : files.get(0);
    }

    /** Mode-aware byte resolution for the image controller. */
    public Optional<StoredImage> resolveTaskImage(String taskId, int index) {
        if (storageManager.isMinioActive()) {
            for (String ext : new String[]{"png", "jpg", "webp"}) {
                String key = taskImageKey(taskId, index, 0, ext);
                Optional<byte[]> bytes = storageManager.active().get(key);
                if (bytes.isPresent()) {
                    return Optional.of(new StoredImage(bytes.get(), contentTypeForExt(ext)));
                }
            }
            return Optional.empty();
        }
        Path file = resolveItemImage(taskId, index);
        if (file == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new StoredImage(Files.readAllBytes(file), contentTypeFor(file)));
        } catch (IOException e) {
            log.warn("[image-storage] 读取失败: {}", file, e);
            return Optional.empty();
        }
    }

    public record StoredImage(byte[] data, String contentType) {
    }

    /** Extension → content type (mirrors the Node map used by ImageController). */
    public static String contentTypeForExt(String ext) {
        return switch (ext.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
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
