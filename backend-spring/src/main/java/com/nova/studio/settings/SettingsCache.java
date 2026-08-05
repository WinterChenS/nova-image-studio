package com.nova.studio.settings;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * HOT-settings cache (ADR-6, T2.1): per-user settings maps with a 1s TTL and
 * write-through invalidation — equivalent to the Node backend's 1s env
 * re-read semantics. A write invalidates the user's entry so the next read is
 * fresh; unmodified entries are served for up to 1s without touching the DB.
 */
@Component
public class SettingsCache {

    private final Cache<UUID, Map<String, String>> cache;

    public SettingsCache() {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1))
                .maximumSize(10_000)
                .build();
    }

    public Map<String, String> get(UUID userId) {
        return cache.getIfPresent(userId);
    }

    public void put(UUID userId, Map<String, String> values) {
        cache.put(userId, values);
    }

    public void invalidate(UUID userId) {
        cache.invalidate(userId);
    }
}
