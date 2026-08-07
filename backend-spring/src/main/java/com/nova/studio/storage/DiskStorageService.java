package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * WIN-22 (ADR-13) — filesystem implementation of {@link ObjectStorageService}
 * (the disk fallback). The root directory is {@code NOVA_IMAGE_DIR} (runtime
 * hot). A key {@code assets/{userId}/{assetId}.png} maps to
 * {@code <root>/assets/{userId}/{assetId}.png}; keys are server-generated so
 * path traversal is structurally impossible.
 */
@Component
public class DiskStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(DiskStorageService.class);

    private final RuntimeEnv runtimeEnv;

    public DiskStorageService(RuntimeEnv runtimeEnv) {
        this.runtimeEnv = runtimeEnv;
    }

    public Path rootDir() {
        return Path.of(runtimeEnv.getString("NOVA_IMAGE_DIR", "./data/nova-images"));
    }

    @Override
    public String mode() {
        return "disk";
    }

    private Path toPath(String key) {
        // keys are server-generated; normalize separators and prevent escape
        String normalized = key.replace('\\', '/');
        Path base = rootDir().toAbsolutePath().normalize();
        Path resolved = base.resolve(normalized).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("非法对象 key: " + key);
        }
        return resolved;
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        Path file = toPath(key);
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, data);
        } catch (IOException e) {
            throw new IllegalStateException("对象写入失败: " + e.getMessage(), e);
        }
        log.debug("[disk-storage] put {}", key);
    }

    @Override
    public Optional<byte[]> get(String key) {
        Path file = toPath(key);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("[disk-storage] 读取失败: {}", key, e);
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String key) {
        Path file = toPath(key);
        try {
            boolean existed = Files.deleteIfExists(file);
            if (existed) {
                log.debug("[disk-storage] deleted {}", key);
            }
            return existed;
        } catch (IOException e) {
            log.warn("[disk-storage] 删除失败: {}", key, e);
            return false;
        }
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(toPath(key));
    }

    @Override
    public List<String> listByPrefix(String prefix) {
        String normalizedPrefix = prefix.replace('\\', '/');
        Path base = rootDir().toAbsolutePath().normalize();
        Path prefixPath = base.resolve(normalizedPrefix).normalize();
        Path start = Files.isDirectory(prefixPath) ? prefixPath : prefixPath.getParent();
        if (start == null || !Files.isDirectory(start)) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(start)) {
            walk.filter(Files::isRegularFile).forEach(file -> {
                String relative = base.relativize(file.toAbsolutePath().normalize())
                        .toString().replace('\\', '/');
                if (relative.startsWith(normalizedPrefix)) {
                    keys.add(relative);
                }
            });
        } catch (IOException e) {
            log.warn("[disk-storage] 前缀列举失败: {}", prefix, e);
            return List.of();
        }
        keys.sort(String::compareTo);
        return keys;
    }

    @Override
    public boolean isHealthy() {
        try {
            Files.createDirectories(rootDir());
            return Files.isWritable(rootDir());
        } catch (IOException e) {
            log.warn("[disk-storage] 健康检查失败", e);
            return false;
        }
    }
}
