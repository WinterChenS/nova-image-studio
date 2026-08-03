package com.nova.studio.infra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime env reader with 1s TTL — port of the Node backend's
 * {@code getRuntimeEnv} ({@code backend/server.js}): the {@code .env} file is
 * re-read at most once per second so edits (rate limits, queue concurrency,
 * gallery mode, ops switches) take effect without a restart, exactly like the
 * Node backend. {@code NOVA_ACCEPT_NEW_TASKS} / {@code NOVA_REJECT_NEW_TASKS}
 * and the limit/gallery knobs stay on this .env layer (A.5-Q4 / H4: ops
 * switches must not live in the DB).
 */
@Component
public class RuntimeEnv {

    private static final Logger log = LoggerFactory.getLogger(RuntimeEnv.class);

    private static final long CACHE_TTL_MS = 1000;

    private final Path envFile;
    private volatile long cacheExpiresAt = 0;
    private volatile Map<String, String> cache = Map.of();

    public RuntimeEnv(@Value("${nova.env-file:.env}") String envFile) {
        this.envFile = Path.of(envFile);
    }

    /** Current merged view: real environment variables overlaid by the .env file. */
    public Map<String, String> snapshot() {
        long now = System.currentTimeMillis();
        if (now >= cacheExpiresAt) {
            Map<String, String> merged = new HashMap<>(System.getenv());
            merged.putAll(EnvFileParser.parse(envFile));
            cache = merged;
            cacheExpiresAt = now + CACHE_TTL_MS;
            log.debug("[runtime-env] refreshed {} entries from {}", merged.size(), envFile);
        }
        return cache;
    }

    public String getString(String key) {
        return snapshot().get(key);
    }

    public String getString(String key, String fallback) {
        String value = getString(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public int getInt(String key, int fallback) {
        String value = getString(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String value = getString(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.trim().toLowerCase();
        return switch (v) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }
}
