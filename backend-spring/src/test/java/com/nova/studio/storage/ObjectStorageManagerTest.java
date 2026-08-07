package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WIN-22 (F-34/R-3) — storage mode selection: explicit {@code MINIO_ENABLED=false}
 * forces disk; missing credentials → disk; configured + healthy probe → minio;
 * configured + failing probe → disk fallback with {@code fallbackActive}.
 */
class ObjectStorageManagerTest {

    private final RuntimeEnv runtimeEnv = mock(RuntimeEnv.class);
    private final MinioStorageService minio = mock(MinioStorageService.class);
    private final DiskStorageService disk = mock(DiskStorageService.class);
    private final ObjectStorageManager manager = new ObjectStorageManager(runtimeEnv, disk, minio);

    private void configured(boolean enabled) {
        when(runtimeEnv.getString("MINIO_ENABLED", "true")).thenReturn(enabled ? "true" : "false");
        when(runtimeEnv.getString("MINIO_ENDPOINT", "")).thenReturn("http://minio:9000");
        when(runtimeEnv.getString("MINIO_ACCESS_KEY", "")).thenReturn("ak");
        when(runtimeEnv.getString("MINIO_SECRET_KEY", "")).thenReturn("sk");
    }

    @Test
    void explicitDisabledForcesDisk() {
        configured(false);
        assertThat(manager.isMinioConfigured()).isFalse();
        assertThat(manager.active()).isSameAs(disk);
        assertThat(manager.health().mode()).isEqualTo("disk");
    }

    @Test
    void missingCredentialsFallsBackToDisk() {
        when(runtimeEnv.getString("MINIO_ENABLED", "true")).thenReturn("true");
        when(runtimeEnv.getString("MINIO_ENDPOINT", "")).thenReturn("");
        when(runtimeEnv.getString("MINIO_ACCESS_KEY", "")).thenReturn("");
        when(runtimeEnv.getString("MINIO_SECRET_KEY", "")).thenReturn("");
        assertThat(manager.isMinioConfigured()).isFalse();
        assertThat(manager.active()).isSameAs(disk);
    }

    @Test
    void healthyMinioIsActive() {
        configured(true);
        when(minio.isHealthy()).thenReturn(true);
        assertThat(manager.isMinioActive()).isTrue();
        assertThat(manager.active()).isSameAs(minio);
        ObjectStorageManager.StorageHealthInfo health = manager.health();
        assertThat(health.mode()).isEqualTo("minio");
        assertThat(health.minioConfigured()).isTrue();
        assertThat(health.fallbackActive()).isFalse();
    }

    @Test
    void unhealthyMinioFallsBackWithFlag() {
        configured(true);
        when(minio.isHealthy()).thenReturn(false);
        assertThat(manager.isMinioActive()).isFalse();
        assertThat(manager.active()).isSameAs(disk);
        ObjectStorageManager.StorageHealthInfo health = manager.health();
        assertThat(health.mode()).isEqualTo("disk");
        assertThat(health.fallbackActive()).isTrue();
    }

    @Test
    void healthExposesBucketAndEndpointWithoutCredentials() {
        configured(true);
        when(minio.isHealthy()).thenReturn(true);
        when(minio.bucketName()).thenReturn("nova-assets");
        when(minio.ensureBucket()).thenReturn(true);
        when(minio.publicEndpointLabel()).thenReturn("192.168.3.225:19000");
        ObjectStorageManager.StorageHealthInfo health = manager.health();
        assertThat(health.bucket()).isEqualTo("nova-assets");
        assertThat(health.bucketExists()).isTrue();
        assertThat(health.endpoint()).isEqualTo("192.168.3.225:19000");
        assertThat(health.lastCheckedAt()).isNotBlank();
    }
}
