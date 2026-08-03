package com.nova.studio.web;

import com.nova.studio.infra.RuntimeEnv;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T1.8 — static file resolution: SPA fallback candidates, 404.html fallback,
 * path traversal protection and missing-dir 404 (Node serveStatic port).
 */
class StaticFileResolverTest {

    @TempDir
    Path tempDir;

    private final RuntimeEnv runtimeEnv = mock(RuntimeEnv.class);

    private StaticFileResolver newResolver() {
        when(runtimeEnv.getString("NOVA_STATIC_DIR", "../frontend/out")).thenReturn(tempDir.toString());
        return new StaticFileResolver(runtimeEnv);
    }

    @Test
    void servesIndexHtmlForRoot() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "<html>root</html>");
        StaticFileResolver.Resolution r = newResolver().resolve("/");
        assertThat(r).isNotNull();
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.file().getFileName().toString()).isEqualTo("index.html");
    }

    @Test
    void spaFallbackAppendsHtmlAndIndexHtml() throws Exception {
        Files.writeString(tempDir.resolve("about.html"), "about-page");
        Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(tempDir.resolve("workspace/index.html"), "workspace-page");
        assertThat(newResolver().resolve("/about").file().getFileName().toString()).isEqualTo("about.html");
        assertThat(newResolver().resolve("/workspace").file().getFileName().toString()).isEqualTo("index.html");
    }

    @Test
    void missingPathFallsBackTo404Html() throws Exception {
        Files.writeString(tempDir.resolve("404.html"), "<html>404</html>");
        StaticFileResolver.Resolution r = newResolver().resolve("/no/such/page");
        assertThat(r).isNotNull();
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.file().getFileName().toString()).isEqualTo("404.html");
    }

    @Test
    void pathTraversalRejected() throws Exception {
        Files.writeString(tempDir.resolve("index.html"), "root");
        Path outside = tempDir.resolveSibling("secret.txt");
        Files.writeString(outside, "secret");
        try {
            assertThat(newResolver().resolve("/../secret.txt")).isNull();
            assertThat(newResolver().resolve("/a/%2e%2e/secret.txt")).isNull();
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void missingStaticDirReturnsNull() {
        StaticFileResolver resolver = new StaticFileResolver(runtimeEnv);
        when(runtimeEnv.getString("NOVA_STATIC_DIR", "../frontend/out"))
                .thenReturn(tempDir.resolve("nope").toString());
        assertThat(resolver.resolve("/")).isNull();
    }
}
