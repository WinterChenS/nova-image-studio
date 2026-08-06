package com.nova.studio.migration;

import com.nova.studio.infra.RuntimeEnv;
import com.nova.studio.storage.MinioStorageService;
import com.nova.studio.storage.ObjectStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WIN-22 (F-36 / ARCH E.5) — idempotent disk → MinIO migration for task
 * images. Scans {@code NOVA_IMAGE_DIR} for flat {@code {taskId}-{itemIndex}-
 * {subIndex}.{ext}} files (the legacy disk naming) and uploads each as
 * {@code tasks/{taskId}/{itemIndex}-{subIndex}.{ext}} (F-31 key rule).
 *
 * <p>Idempotent: {@code statObject} (via {@code exists()}) skips keys already
 * present, so re-running only uploads what is still missing — safe to run any
 * number of times. A summary report (uploaded/skipped/failed) is printed and
 * failed files are logged for retry. Rollback = keep the disk files untouched
 * (the disk directory is never modified).
 *
 * <p>Run via {@code backend-spring/scripts/migrate-disk-to-minio.sh} (with
 * {@code .env} exported so MINIO_* credentials are picked up).
 */
public final class DiskToMinioMigrator {

    private static final Logger log = LoggerFactory.getLogger(DiskToMinioMigrator.class);

    /** Matches Node disk naming: {taskId}-{itemIndex}-{subIndex}.{ext}. */
    private static final Pattern FILE_PATTERN =
            Pattern.compile("^([A-Za-z0-9-]+)-(\\d+)-(\\d+)\\.([a-zA-Z0-9]+)$");

    private DiskToMinioMigrator() {
    }

    public static void main(String[] args) throws Exception {
        String imageDir = System.getenv("NOVA_IMAGE_DIR") == null
                ? "./data/nova-images" : System.getenv("NOVA_IMAGE_DIR");
        String envFile = System.getenv("NOVA_ENV_FILE") == null ? ".env" : System.getenv("NOVA_ENV_FILE");

        RuntimeEnv runtimeEnv = new RuntimeEnv(envFile);
        MinioStorageService minio = new MinioStorageService(runtimeEnv);

        if (!minio.isHealthy()) {
            System.err.println("ERROR: MinIO 不可达或未配置 — 请确认 .env 中 MINIO_ENDPOINT/ACCESS_KEY/SECRET_KEY 正确");
            System.exit(2);
        }

        Path dir = Path.of(imageDir);
        if (!Files.isDirectory(dir)) {
            System.out.println("磁盘图片目录不存在（无存量可迁移）: " + dir.toAbsolutePath());
            return;
        }

        List<Path> files = listFlatTaskImages(dir);
        int uploaded = 0;
        int skipped = 0;
        int failed = 0;
        List<String> failures = new ArrayList<>();

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            Matcher m = FILE_PATTERN.matcher(fileName);
            if (!m.matches()) {
                continue; // 非任务图命名，跳过（不属于本次迁移范围）
            }
            String taskId = m.group(1);
            String itemIndex = m.group(2);
            String subIndex = m.group(3);
            String ext = m.group(4).toLowerCase();
            String key = "tasks/" + taskId + "/" + itemIndex + "-" + subIndex + "." + ext;
            try {
                if (minio.exists(key)) {
                    skipped++;
                    continue; // 幂等：已存在则跳过
                }
                byte[] data = Files.readAllBytes(file);
                minio.put(key, data, contentTypeForExt(ext));
                uploaded++;
                log.info("[disk-to-minio] 已上传: {} ({})", key, data.length);
            } catch (Exception e) {
                failed++;
                failures.add(fileName + " -> " + e.getMessage());
                log.warn("[disk-to-minio] 上传失败: {} -> {}", fileName, e.getMessage());
            }
        }

        System.out.println("== 磁盘→MinIO 迁移完成 ==");
        System.out.println("  扫描文件: " + files.size());
        System.out.println("  已上传:   " + uploaded);
        System.out.println("  已跳过:   " + skipped);
        System.out.println("  失败:     " + failed);
        if (!failures.isEmpty()) {
            System.out.println("  失败清单（可重跑补传）:");
            failures.forEach(f -> System.out.println("    - " + f));
        }
        if (failed > 0) {
            System.exit(1); // 非零退出，供脚本重试判定
        }
    }

    /** Flat task-image files only (skip subdirectories / non-task-named files). */
    static List<Path> listFlatTaskImages(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isRegularFile(p) && FILE_PATTERN.matcher(p.getFileName().toString()).matches()) {
                    files.add(p);
                }
            }
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    static String contentTypeForExt(String ext) {
        return switch (ext.toLowerCase()) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "gif" -> "image/gif";
            default -> "application/octet-stream";
        };
    }
}
