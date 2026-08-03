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
 * T1.4 — image storage: extension mapping, file naming, item resolution and
 * per-task deletion (Node saveImageToDisk / getTaskImageFiles / resolveItemImage).
 */
class ImageStorageServiceTest {

    @TempDir
    Path tempDir;

    private final RuntimeEnv runtimeEnv = mock(RuntimeEnv.class);
    private ImageStorageService service;

    private ImageStorageService newService() {
        when(runtimeEnv.getString("NOVA_IMAGE_DIR", "./data/nova-images")).thenReturn(tempDir.toString());
        return new ImageStorageService(runtimeEnv);
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
        // prefix fallback (sub-index 0 missing)
        Files.delete(tempDir.resolve("t1-0-0.jpg"));
        assertThat(service.resolveItemImage("t1", 0).getFileName().toString()).isEqualTo("t1-0-1.png");
        assertThat(service.resolveItemImage("t1", 9)).isNull();
    }

    @Test
    void deletesAllTaskImageFiles() throws IOException {
        service = newService();
        Files.write(tempDir.resolve("t1-0-0.png"), new byte[]{1});
        Files.write(tempDir.resolve("t1-1-0.png"), new byte[]{2});
        Files.write(tempDir.resolve("other-0-0.png"), new byte[]{3});
        int deleted = service.deleteTaskImageFiles("t1");
        assertThat(deleted).isEqualTo(2);
        assertThat(tempDir.resolve("other-0-0.png")).exists();
    }

    @Test
    void listTaskFilesByPrefix() throws IOException {
        service = newService();
        Files.write(tempDir.resolve("t9-0-0.png"), new byte[]{1});
        Files.write(tempDir.resolve("t90-0-0.png"), new byte[]{2});
        assertThat(service.getTaskImageFiles("t9")).hasSize(1);
        assertThat(service.getTaskImageFiles("t90")).hasSize(1);
    }
}
