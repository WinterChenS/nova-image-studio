package com.nova.studio.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Redis read/write probe for the M0 spike (T0.1). Demonstrates that the
 * application can connect to the configured Redis and round-trip a value.
 */
@Component
public class RedisProbe {

    private static final Logger log = LoggerFactory.getLogger(RedisProbe.class);

    private final StringRedisTemplate redisTemplate;

    public RedisProbe(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Sets a key with a 60s TTL and reads it back. Returns the read value. */
    public String roundTrip(String key, String value) {
        redisTemplate.opsForValue().set(key, value, Duration.ofSeconds(60));
        String readBack = redisTemplate.opsForValue().get(key);
        log.info("[redis-probe] SET {}={} → GET {}={}", key, value, key, readBack);
        return readBack;
    }

    public void delete(String key) {
        redisTemplate.delete(key);
    }
}
