package com.nova.studio.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-22 (F-36) — disk→MinIO migration helpers: flat task-image listing and
 * the key/extension mapping (idempotent stat-skip lives in the migrator flow).
 */
class DiskToMinioMigratorTest {

    private static final Pattern FILE_PATTERN =
            Pattern.compile("^([A-Za-z0-9-]+)-(\\d+)-(\\d+)\\.([a-zA-Z0-9]+)$");

    @TempDir
    Path tempDir;

    @Test
    void listsOnlyFlatTaskImageFiles() throws Exception {
        Files.write(tempDir.resolve("t1-0-0.png"), new byte[]{1});
        Files.write(tempDir.resolve("t1-0-1.jpg"), new byte[]{2});
        Files.write(tempDir.resolve("readme.txt"), new byte[]{3});
        Files.createDirectories(tempDir.resolve("nested"));
        Files.write(tempDir.resolve("nested/t2-0-0.png"), new byte[]{4});

        List<Path> files = DiskToMinioMigrator.listFlatTaskImages(tempDir);

        assertThat(files).extracting(p -> p.getFileName().toString())
                .containsExactly("t1-0-0.png", "t1-0-1.jpg");
    }

    @Test
    void parsesNodeNamingIntoTaskKey() {
        Matcher m = FILE_PATTERN.matcher("t-123-0-0.png");
        assertThat(m.matches()).isTrue();
        assertThat(m.group(1)).isEqualTo("t-123");
        assertThat(m.group(2)).isEqualTo("0");
        assertThat(m.group(3)).isEqualTo("0");
        assertThat(m.group(4)).isEqualTo("png");
        // key 与 F-31 一致：tasks/{taskId}/{index}-{sub}.{ext}
        assertThat("tasks/" + m.group(1) + "/" + m.group(2) + "-" + m.group(3) + "." + m.group(4))
                .isEqualTo("tasks/t-123/0-0.png");
    }

    @Test
    void mapsExtensionToContentType() {
        assertThat(DiskToMinioMigrator.contentTypeForExt("png")).isEqualTo("image/png");
        assertThat(DiskToMinioMigrator.contentTypeForExt("jpg")).isEqualTo("image/jpeg");
        assertThat(DiskToMinioMigrator.contentTypeForExt("webp")).isEqualTo("image/webp");
        assertThat(DiskToMinioMigrator.contentTypeForExt("bin")).isEqualTo("application/octet-stream");
    }
}
