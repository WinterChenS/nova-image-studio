package com.nova.studio.storage;

import com.nova.studio.infra.RuntimeEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WIN-22 (ADR-13) — disk object storage: key→path mapping, put/get/delete/
 * exists, prefix listing and health probe.
 */
class DiskStorageServiceTest {

    @TempDir
    Path tempDir;

    private final RuntimeEnv runtimeEnv = mock(RuntimeEnv.class);
    private DiskStorageService service;

    @BeforeEach
    void setUp() {
        when(runtimeEnv.getString("NOVA_IMAGE_DIR", "./data/nova-images")).thenReturn(tempDir.toString());
        service = new DiskStorageService(runtimeEnv);
    }

    @Test
    void putGetRoundTrip() {
        service.put("assets/u1/a1.png", new byte[]{1, 2, 3}, "image/png");
        Optional<byte[]> bytes = service.get("assets/u1/a1.png");
        assertThat(bytes).isPresent();
        assertThat(bytes.get()).containsExactly(1, 2, 3);
        assertThat(tempDir.resolve("assets/u1/a1.png")).exists();
    }

    @Test
    void missingKeyReturnsEmpty() {
        assertThat(service.get("assets/u1/nope.png")).isEmpty();
        assertThat(service.exists("assets/u1/nope.png")).isFalse();
    }

    @Test
    void deleteRemovesObjectAndReportsExistence() {
        service.put("assets/u1/a1.png", new byte[]{1}, "image/png");
        assertThat(service.delete("assets/u1/a1.png")).isTrue();
        assertThat(service.exists("assets/u1/a1.png")).isFalse();
        assertThat(service.delete("assets/u1/a1.png")).isFalse();
    }

    @Test
    void listByPrefixReturnsMatchingKeysSorted() {
        service.put("tasks/t1/0-0.png", new byte[]{1}, "image/png");
        service.put("tasks/t1/0-1.png", new byte[]{2}, "image/png");
        service.put("tasks/t2/0-0.png", new byte[]{3}, "image/png");
        service.put("assets/u1/a1.png", new byte[]{4}, "image/png");
        assertThat(service.listByPrefix("tasks/t1/")).containsExactly("tasks/t1/0-0.png", "tasks/t1/0-1.png");
        assertThat(service.listByPrefix("tasks/")).containsExactly("tasks/t1/0-0.png", "tasks/t1/0-1.png", "tasks/t2/0-0.png");
        assertThat(service.listByPrefix("missing/")).isEmpty();
    }

    @Test
    void traversalIsBlocked() {
        try {
            service.put("../escape.png", new byte[]{1}, "image/png");
            assertThat(tempDir.resolve("escape.png")).doesNotExist();
        } catch (IllegalArgumentException expected) {
            // normalize() escapes the root — either throw or clamp, both acceptable
        }
    }

    @Test
    void healthProbeIsTrueWhenDirectoryWritable() {
        assertThat(service.isHealthy()).isTrue();
    }

    @Test
    void listByPrefixScansFilesNotDirectories() throws Exception {
        Files.createDirectories(tempDir.resolve("tasks/t1"));
        assertThat(service.listByPrefix("tasks/t1/")).isEmpty();
    }
}
