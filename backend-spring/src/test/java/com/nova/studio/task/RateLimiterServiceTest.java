package com.nova.studio.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1.10 — fixed-window rate limiter semantics (Node consumeRateLimit port):
 * allowed until the window cap, then blocked until the window resets.
 */
class RateLimiterServiceTest {

    @Test
    void allowsUpToCapacityWithinWindow() {
        RateLimiterService limiter = new RateLimiterService();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.consume("ip:1.2.3.4", 3, 60_000)).isZero();
        }
        assertThat(limiter.consume("ip:1.2.3.4", 3, 60_000)).isGreaterThan(0);
    }

    @Test
    void windowsAreIndependentPerKey() {
        RateLimiterService limiter = new RateLimiterService();
        limiter.consume("ip:a", 1, 60_000);
        assertThat(limiter.consume("ip:b", 1, 60_000)).isZero();
        assertThat(limiter.consume("ip:a", 1, 60_000)).isGreaterThan(0);
    }

    @Test
    void windowResetsAfterElapse() throws InterruptedException {
        RateLimiterService limiter = new RateLimiterService();
        assertThat(limiter.consume("ip:x", 1, 50)).isZero();
        assertThat(limiter.consume("ip:x", 1, 50)).isGreaterThan(0);
        Thread.sleep(80);
        assertThat(limiter.consume("ip:x", 1, 50)).isZero();
    }

    @Test
    void zeroCapacityAlwaysBlocksWithWindowSeconds() {
        RateLimiterService limiter = new RateLimiterService();
        assertThat(limiter.consume("ip:y", 0, 60_000)).isEqualTo(60);
    }
}
