package com.nova.studio.web;

import com.nova.studio.infra.RuntimeEnv;
import org.springframework.stereotype.Component;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Static file resolution (T1.8) — port of the Node backend's {@code serveStatic}
 * ({@code backend/server.js}): serves the Next.js static export
 * ({@code frontend/out/}, configurable via {@code NOVA_STATIC_DIR}) with SPA
 * fallback candidates ({@code path} → {@code path.html} →
 * {@code path/index.html}), {@code 404.html} fallback with 404 status and path
 * traversal protection ({@code ..} segments rejected before resolution).
 *
 * <p>Used by {@link StaticResourceFilter}, which runs before Spring MVC handler
 * mapping — this avoids the {@code /**} controller catch-all shadowing the
 * WebSocket endpoint (WebSocketHandlerMapping has order 1, behind
 * RequestMappingHandlerMapping order 0).
 */
@Component
public class StaticFileResolver {

    private final RuntimeEnv runtimeEnv;

    public StaticFileResolver(RuntimeEnv runtimeEnv) {
        this.runtimeEnv = runtimeEnv;
    }

    /** Result: the file to serve and its HTTP status; null when no static dir. */
    public record Resolution(Path file, int status) {
    }

    /** Returns null when the static dir is missing or the path must 404 with no page. */
    public Resolution resolve(String requestPath) {
        Path dir = staticDir();
        if (!Files.isDirectory(dir)) {
            return null;
        }
        String decoded = decodeSafely(requestPath == null || requestPath.isEmpty() ? "/"
                : (requestPath.startsWith("/") ? requestPath : "/" + requestPath));
        if (decoded.contains("..")) {
            return null;
        }
        String rel = decoded.startsWith("/") ? decoded.substring(1) : decoded;
        if (!rel.isEmpty() && Path.of(rel).normalize().toString().contains("..")) {
            return null;
        }

        List<String> candidates;
        if (rel.isEmpty() || rel.endsWith("/")) {
            candidates = List.of(rel + "index.html");
        } else {
            candidates = List.of(rel, rel + ".html", rel + "/index.html");
        }

        Path root = dir.toAbsolutePath().normalize();
        for (String candidate : candidates) {
            Path resolved = root.resolve(candidate).normalize();
            if (resolved.startsWith(root) && Files.isRegularFile(resolved)) {
                return new Resolution(resolved, 200);
            }
        }
        Path notFound = root.resolve("404.html").normalize();
        if (notFound.startsWith(root) && Files.isRegularFile(notFound)) {
            return new Resolution(notFound, 404);
        }
        return null;
    }

    private Path staticDir() {
        return Path.of(runtimeEnv.getString("NOVA_STATIC_DIR", "../frontend/out"));
    }

    private static String decodeSafely(String path) {
        try {
            return URLDecoder.decode(path, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return path.replaceAll("%(?![0-9a-fA-F]{2})", "");
        }
    }
}
