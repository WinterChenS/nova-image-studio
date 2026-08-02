package com.nova.studio.integration;

import com.nova.studio.redis.RedisProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0.1 — Redis connectivity against the external server.
 *
 * <p>Runs only when {@code REDIS_HOST} is set; skipped in CI without the server.
 */
@SpringBootTest(properties = {
        "nova.ai.openai.enabled=false",
        "nova.spike.verify=false"
})
@EnabledIfEnvironmentVariable(named = "REDIS_HOST", matches = ".+")
class RedisConnectivityIntegrationTest {

    @Autowired
    private RedisProbe redisProbe;

    @Test
    void redisSetGetRoundTrip() {
        String key = "spike:m0:" + System.nanoTime();
        try {
            String readBack = redisProbe.roundTrip(key, "spike-ok");
            assertThat(readBack).isEqualTo("spike-ok");
        } finally {
            redisProbe.delete(key);
        }
    }
}
