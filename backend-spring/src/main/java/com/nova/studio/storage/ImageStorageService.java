package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Disk image storage + hosting lookup (T1.4) — port of the Node backend's
 * {@code saveImageToDisk} / {@code getTaskImageFiles} / {@code deleteTaskImageFiles}.
 * File naming stays exactly {@code {taskId}-{itemIndex}-{subIndex}.{ext}} with
 * extension derived from the mime type (jpeg/jpg → jpg, webp → webp, else png).
 * The directory is {@code NOVA_IMAGE_DIR} (runtime-hot, A.5-Q8/H8: disk + interface
 * so an object store can be added in P2).
 */
@Service
public class ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);

    private final RuntimeEnv runtimeEnv;

    public ImageStorageService(RuntimeEnv runtimeEnv) {
        this.runtimeEnv = runtimeEnv;
    }

    public Path imageDir() {
        return Path.of(runtimeEnv.getString("NOVA_IMAGE_DIR", "./data/nova-images"));
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

    /** Writes an image buffer to disk; returns the public http URL. */
    public String saveImageToDisk(String taskId, int itemIndex, int subIndex, byte[] imageBuffer, String mimeType) {
        String ext = getImageExtension(mimeType);
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

    /** Downloads a remote image URL to disk. */
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

    /** Deletes all image files belonging to a task. */
    public int deleteTaskImageFiles(String taskId) {
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
     * scan), then falls back to a prefix scan — port of the Node handler.
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
}
