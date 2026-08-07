package com.nova.studio.storage;

import java.util.List;
import java.util.Optional;

/**
 * WIN-22 (ADR-13) — object storage abstraction (the ADR-10 P2 extension point,
 * now landed). Two implementations exist: {@link MinioStorageService} (object
 * store) and {@link DiskStorageService} (filesystem fallback). Keys are
 * server-generated and never trust client filenames (ADR-16).
 *
 * <p>Task images keep their own naming scheme on disk (flat
 * {@code {taskId}-{index}-{sub}.{ext}}) vs MinIO ({@code tasks/{taskId}/...}),
 * so the {@link ImageStorageService} facade handles mode-aware task keys;
 * this interface is the generic object store used by assets.
 */
public interface ObjectStorageService {

    /** Storage mode: {@code "minio"} or {@code "disk"}. */
    String mode();

    /** Stores bytes under a key. */
    void put(String key, byte[] data, String contentType);

    /** Reads bytes for a key (empty when missing). */
    Optional<byte[]> get(String key);

    /** Deletes a key; returns whether the object existed. */
    boolean delete(String key);

    /** Whether a key exists. */
    boolean exists(String key);

    /** Lists keys under a prefix (used for task cleanup + migration). */
    List<String> listByPrefix(String prefix);

    /** Liveness probe (MinIO: bucket reachable; disk: directory writable). */
    boolean isHealthy();
}
