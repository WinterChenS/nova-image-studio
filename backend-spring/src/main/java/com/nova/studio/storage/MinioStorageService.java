package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Item;
import okhttp3.OkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * WIN-22 (ADR-13/ADR-20) — MinIO implementation of {@link ObjectStorageService}.
 * The client is built lazily from {@code .env} (MINIO_ENDPOINT / ACCESS_KEY /
 * SECRET_KEY / BUCKET / REGION); credentials never leave the Spring config
 * layer (no DB, no logs, no frontend bundle). {@code ensureBucket} runs once
 * per successful client build (private ACL by default).
 */
@Component
public class MinioStorageService implements ObjectStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private static final int HEALTH_TIMEOUT_SECONDS = 5;

    private final RuntimeEnv runtimeEnv;

    private volatile MinioClient client;
    private volatile boolean bucketEnsured = false;
    private volatile long lastFailureAt = 0;

    public MinioStorageService(RuntimeEnv runtimeEnv) {
        this.runtimeEnv = runtimeEnv;
    }

    public String bucketName() {
        return runtimeEnv.getString("MINIO_BUCKET", "nova-assets");
    }

    private String region() {
        return runtimeEnv.getString("MINIO_REGION", "us-east-1");
    }

    /** Public host:port (no credentials) — safe for the health endpoint. */
    public String publicEndpointLabel() {
        String endpoint = runtimeEnv.getString("MINIO_ENDPOINT", "");
        if (endpoint.isBlank()) {
            return "";
        }
        return endpoint.replaceFirst("^https?://", "").replaceFirst("/+$", "");
    }

    /** Lazy client build — returns null when MinIO is not configured. */
    public synchronized MinioClient clientOrNull() {
        if (client != null) {
            return client;
        }
        String endpoint = runtimeEnv.getString("MINIO_ENDPOINT", "");
        String accessKey = runtimeEnv.getString("MINIO_ACCESS_KEY", "");
        String secretKey = runtimeEnv.getString("MINIO_SECRET_KEY", "");
        if (endpoint.isBlank() || accessKey.isBlank() || secretKey.isBlank()) {
            return null;
        }
        try {
            OkHttpClient httpClient = new OkHttpClient.Builder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .readTimeout(Duration.ofSeconds(60))
                    .writeTimeout(Duration.ofSeconds(60))
                    .build();
            client = MinioClient.builder()
                    .endpoint(endpoint)
                    .credentials(accessKey, secretKey)
                    .httpClient(httpClient)
                    .build();
            log.info("[minio-storage] client built for endpoint {}", publicEndpointLabel());
        } catch (Exception e) {
            log.warn("[minio-storage] 客户端构建失败: {}", e.getMessage());
            return null;
        }
        return client;
    }

    @Override
    public String mode() {
        return "minio";
    }

    /** Ensures the bucket exists (private ACL). Fails soft so the caller falls back to disk. */
    public synchronized boolean ensureBucket() {
        MinioClient minio = clientOrNull();
        if (minio == null) {
            return false;
        }
        if (bucketEnsured) {
            return true;
        }
        try {
            String bucket = bucketName();
            boolean exists = minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).region(region()).build());
                log.info("[minio-storage] bucket 已创建: {}", bucket);
            }
            bucketEnsured = true;
            return true;
        } catch (Exception e) {
            log.warn("[minio-storage] ensureBucket 失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public void put(String key, byte[] data, String contentType) {
        MinioClient minio = requireClient();
        try {
            ensureBucket();
            minio.putObject(PutObjectArgs.builder()
                    .bucket(bucketName())
                    .object(key)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .build());
            log.debug("[minio-storage] put {}", key);
        } catch (Exception e) {
            throw new IllegalStateException("MinIO 写入失败: " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<byte[]> get(String key) {
        MinioClient minio = requireClient();
        try (InputStream stream = minio.getObject(GetObjectArgs.builder()
                .bucket(bucketName())
                .object(key)
                .build())) {
            return Optional.of(stream.readAllBytes());
        } catch (Exception e) {
            log.debug("[minio-storage] 读取失败(可能不存在): {} -> {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String key) {
        MinioClient minio = requireClient();
        try {
            minio.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName())
                    .object(key)
                    .build());
            return true;
        } catch (Exception e) {
            log.warn("[minio-storage] 删除失败: {}", key, e);
            return false;
        }
    }

    @Override
    public boolean exists(String key) {
        MinioClient minio = requireClient();
        try {
            minio.statObject(StatObjectArgs.builder()
                    .bucket(bucketName())
                    .object(key)
                    .build());
            return true;
        } catch (ErrorResponseException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<String> listByPrefix(String prefix) {
        MinioClient minio = requireClient();
        List<String> keys = new ArrayList<>();
        try {
            Iterable<io.minio.Result<Item>> results = minio.listObjects(ListObjectsArgs.builder()
                    .bucket(bucketName())
                    .prefix(prefix)
                    .build());
            for (io.minio.Result<Item> result : results) {
                keys.add(result.get().objectName());
            }
        } catch (Exception e) {
            log.warn("[minio-storage] 前缀列举失败: {}", prefix, e);
            return List.of();
        }
        keys.sort(String::compareTo);
        return keys;
    }

    @Override
    public boolean isHealthy() {
        MinioClient minio = clientOrNull();
        if (minio == null) {
            return false;
        }
        // 短缓存：避免每请求探测的延迟，同时避免长时间 stale fallback（ARCH 评审建议项）
        long now = System.currentTimeMillis();
        if (now - lastFailureAt < 10_000) {
            return false;
        }
        try {
            boolean ok = ensureBucket();
            if (!ok) {
                lastFailureAt = now;
                log.warn("[minio-storage] 健康检查失败（已降级至 disk，10s 内不再重复探测）");
            }
            return ok;
        } catch (Exception e) {
            lastFailureAt = now;
            log.warn("[minio-storage] 健康检查失败: {}", e.getMessage());
            return false;
        }
    }

    private MinioClient requireClient() {
        MinioClient minio = clientOrNull();
        if (minio == null) {
            throw new IllegalStateException("MinIO 未配置");
        }
        return minio;
    }
}
