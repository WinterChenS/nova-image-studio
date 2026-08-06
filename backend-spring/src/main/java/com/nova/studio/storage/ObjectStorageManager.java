package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * WIN-22 (F-34/R-3) — storage mode orchestrator. Decides the active
 * {@link ObjectStorageService}:
 *
 * <pre>
 *   MINIO_ENABLED=false            → disk (explicit)
 *   MinIO not fully configured     → disk
 *   configured + healthy probe     → minio
 *   configured + probe fails       → disk fallback (fallbackActive=true)
 * </pre>
 *
 * <p>Degradation semantics (ARCH E.4): while degraded, writes go to disk only
 * (no dual-write); after MinIO recovers, the migration script backfills. The
 * health probe result is cached briefly (10s) so requests do not pay per-call
 * latency while a stale fallback is avoided. Storage mode transitions are
 * logged under the {@code [storage-mode]} marker (N-6).
 */
@Component
public class ObjectStorageManager {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorageManager.class);

    private final RuntimeEnv runtimeEnv;
    private final DiskStorageService diskStorageService;
    private final MinioStorageService minioStorageService;

    private volatile long lastModeLogAt = 0;

    public ObjectStorageManager(RuntimeEnv runtimeEnv,
                                DiskStorageService diskStorageService,
                                MinioStorageService minioStorageService) {
        this.runtimeEnv = runtimeEnv;
        this.diskStorageService = diskStorageService;
        this.minioStorageService = minioStorageService;
    }

    public boolean isMinioConfigured() {
        if ("false".equalsIgnoreCase(runtimeEnv.getString("MINIO_ENABLED", "true"))) {
            return false;
        }
        String endpoint = runtimeEnv.getString("MINIO_ENDPOINT", "");
        String accessKey = runtimeEnv.getString("MINIO_ACCESS_KEY", "");
        String secretKey = runtimeEnv.getString("MINIO_SECRET_KEY", "");
        return !endpoint.isBlank() && !accessKey.isBlank() && !secretKey.isBlank();
    }

    /** Whether MinIO is configured AND currently healthy (probe cached 10s). */
    public boolean isMinioActive() {
        return isMinioConfigured() && minioStorageService.isHealthy();
    }

    /** The active storage service (minio when healthy, else disk fallback). */
    public ObjectStorageService active() {
        boolean minio = isMinioActive();
        long now = System.currentTimeMillis();
        if (now - lastModeLogAt > 60_000) {
            log.info("[storage-mode] active={}, minioConfigured={}", minio ? "minio" : "disk", isMinioConfigured());
            lastModeLogAt = now;
        }
        return minio ? minioStorageService : diskStorageService;
    }

    /** Health snapshot for GET /api/nova/storage/health (F-35). */
    public StorageHealthInfo health() {
        boolean configured = isMinioConfigured();
        boolean reachable = configured && minioStorageService.isHealthy();
        String bucket = configured ? minioStorageService.bucketName() : null;
        boolean bucketExists = reachable && minioStorageService.ensureBucket();
        boolean fallbackActive = configured && !reachable;
        return new StorageHealthInfo(
                reachable ? "minio" : "disk",
                configured,
                bucket,
                bucketExists,
                configured ? minioStorageService.publicEndpointLabel() : "",
                reachable,
                fallbackActive,
                java.time.Instant.now().toString());
    }

    /** Public health shape (no credentials — ADR-20). */
    public record StorageHealthInfo(String mode, boolean minioConfigured, String bucket,
                                    boolean bucketExists, String endpoint, boolean reachable,
                                    boolean fallbackActive, String lastCheckedAt) {
    }
}
