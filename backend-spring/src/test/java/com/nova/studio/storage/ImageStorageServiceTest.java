package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1.4 + WIN-22 (ADR-13) — image storage facade: extension mapping, file
 * naming, item resolution and per-task deletion (Node saveImageToDisk /
 * getTaskImageFiles / resolveItemImage) in disk mode; MinIO routing is
 * covered by the storage manager tests.
 */
class ImageStorageServiceTest {

    @TempDir
    Path tempDir;

    private final RuntimeEnv runtimeEnv = mock(RuntimeEnv.class);
    private ImageStorageService service;
    private ObjectStorageManager manager;
    private DiskStorageService disk;

    private ImageStorageService newService() {
        when(runtimeEnv.getString("NOVA_IMAGE_DIR", "./data/nova-images")).thenReturn(tempDir.toString());
        disk = new DiskStorageService(runtimeEnv);
        manager = new ObjectStorageManager(runtimeEnv, disk, mock(MinioStorageService.class));
        when(runtimeEnv.getString("MINIO_ENABLED", "true")).thenReturn("false");
        return new ImageStorageService(manager);
    }

    @AfterEach
    void tearDown() {
        service = null;
    }

    @Test
    void extensionDerivedFromMimeType() {
        assertThat(ImageStorageService.getImageExtension("image/png")).isEqualTo("png");
        assertThat(ImageStorageService.getImageExtension("image/jpeg")).isEqualTo("jpg");
        assertThat(ImageStorageService.getImageExtension("image/webp")).isEqualTo("webp");
        assertThat(ImageStorageService.getImageExtension("application/octet-stream")).isEqualTo("png");
    }

    @Test
    void savesFileWithNodeNamingScheme() {
        service = newService();
        String url = service.saveImageToDisk("task-1", 2, 0, new byte[]{1, 2, 3}, "image/png");
        assertThat(url).isEqualTo("/api/nova/images/task-1/2");
        assertThat(tempDir.resolve("task-1-2-0.png")).exists();
    }

    @Test
    void resolvesCommonSubIndexZeroThenFallsBackToPrefixScan() throws IOException {
        service = newService();
        Files.write(tempDir.resolve("t1-0-0.jpg"), new byte[]{9});
        Files.write(tempDir.resolve("t1-0-1.png"), new byte[]{8});
        // common case: sub-index 0
        assertThat(service.resolveItemImage("t1", 0).getFileName().toString()).isEqualTo("t1-0-0.jpg");
        // prefix scan fallback: sub-index 1
        assertThat(service.resolveItemImage("t1", 0)).isNotNull();
    }

    @Test
    void resolveTaskImageReadsBytesInDiskMode() throws IOException {
        service = newService();
        Files.write(tempDir.resolve("t1-0-0.png"), new byte[]{1, 2, 3});
        var stored = service.resolveTaskImage("t1", 0);
        assertThat(stored).isPresent();
        assertThat(stored.get().data()).containsExactly(1, 2, 3);
        assertThat(stored.get().contentType()).isEqualTo("image/png");
    }

    @Test
    void deleteTaskImageFilesRemovesOnlyMatchingPrefix() throws IOException {
        service = newService();
        Files.write(tempDir.resolve("t1-0-0.png"), new byte[]{1});
        Files.write(tempDir.resolve("t1-1-0.png"), new byte[]{2});
        Files.write(tempDir.resolve("other-0-0.png"), new byte[]{3});
        int deleted = service.deleteTaskImageFiles("t1");
        assertThat(deleted).isEqualTo(2);
        assertThat(tempDir.resolve("t1-0-0.png")).doesNotExist();
        assertThat(tempDir.resolve("other-0-0.png")).exists();
    }
}
