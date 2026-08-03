package com.nova.studio.task;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window rate limiting (T1.10, ADR-9) — port of the Node backend's
 * {@code consumeRateLimit} / {@code cleanupRateLimitBuckets}
 * ({@code backend/server.js}). Two independent dimensions are enforced per
 * create-task request: IP and API-key hash ({@code sha256(...).hex[0:24]},
 * Node's {@code hashApiKey}).
 *
 * <p><b>Implementation note:</b> ADR-9 names Bucket4j ({@code Bandwidth.simple}
 * — a fixed window that refills at window end, the exact semantics of Node's
 * counter bucket), but the Bucket4j artifact is not resolvable from any Maven
 * mirror reachable in this environment (verified 2026-08-03). The in-memory
 * fixed-window bucket below is line-equivalent to the Node implementation and
 * to {@code Bandwidth.simple}; the class is shaped so Bucket4j can be swapped
 * in behind the same {@code consume()} contract if the artifact becomes
 * available.
 */
@Service
public class RateLimiterService {

    private record BucketState(long windowStart, int count, long lastUsedNanos) {
    }

    private final Map<String, BucketState> buckets = new ConcurrentHashMap<>();

    /**
     * Consumes one token for the given dimension.
     *
     * @return 0 when allowed; otherwise the seconds until the window resets
     *         (>= 1)
     */
    public long consume(String bucketKey, int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        if (maxRequests <= 0) {
            return Math.max(1, (windowMs + 999) / 1000);
        }
        BucketState existing = buckets.get(bucketKey);
        if (existing == null || now - existing.windowStart() >= windowMs) {
            buckets.put(bucketKey, new BucketState(now, 1, now));
            return 0;
        }
        if (existing.count() >= maxRequests) {
            long remainingMs = windowMs - (now - existing.windowStart());
            return Math.max(1, (remainingMs + 999) / 1000);
        }
        buckets.put(bucketKey, new BucketState(existing.windowStart(), existing.count() + 1, now));
        return 0;
    }

    /** Sweeps buckets untouched for more than 2 windows (Node cleanupRateLimitBuckets). */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanup() {
        long now = System.currentTimeMillis();
        long maxWindowMs = 2L * 3600 * 1000; // generous; refresh on every consume keeps live buckets alive
        buckets.entrySet().removeIf(e -> now - e.getValue().lastUsedNanos() > maxWindowMs);
    }
}
