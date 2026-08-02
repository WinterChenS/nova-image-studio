package com.nova.studio.config;

import com.nova.studio.redis.RedisProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Startup connectivity verifier for the M0 spike (T0.1): enabled with
 * {@code nova.spike.verify=true} (env {@code NOVA_SPIKE_VERIFY=true}).
 *
 * <p>On startup it (a) reads back the Flyway-migrated table to prove Flyway
 * applied migrations against PostgreSQL and (b) performs a Redis SET/GET
 * round-trip. All evidence is written to the application log.
 */
@Component
@ConditionalOnProperty(name = "nova.spike.verify", havingValue = "true")
public class SpikeConnectivityVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SpikeConnectivityVerifier.class);

    private final JdbcTemplate jdbcTemplate;
    private final RedisProbe redisProbe;
    private final String redisKeyPrefix;

    public SpikeConnectivityVerifier(JdbcTemplate jdbcTemplate,
                                     RedisProbe redisProbe,
                                     @Value("${nova.spike.redis-key-prefix:spike}") String redisKeyPrefix) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisProbe = redisProbe;
        this.redisKeyPrefix = redisKeyPrefix;
    }

    @Override
    public void run(ApplicationArguments args) {
        verifyFlywayAndPg();
        verifyRedis();
    }

    private void verifyFlywayAndPg() {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT id, note, created_at FROM nova_spike.spike_probe ORDER BY id");
            log.info("[spike-verify] PostgreSQL OK — nova_spike.spike_probe rows={}", rows);
            List<Map<String, Object>> migrations = jdbcTemplate.queryForList(
                    "SELECT version, description, success FROM nova_spike.flyway_schema_history ORDER BY installed_rank");
            log.info("[spike-verify] Flyway migrations applied={}", migrations);
        } catch (Exception e) {
            log.error("[spike-verify] PostgreSQL/Flyway verification FAILED: {}", e.getMessage(), e);
        }
    }

    private void verifyRedis() {
        try {
            String key = redisKeyPrefix + ":m0:" + System.currentTimeMillis();
            String readBack = redisProbe.roundTrip(key, "spike-ok");
            redisProbe.delete(key);
            if ("spike-ok".equals(readBack)) {
                log.info("[spike-verify] Redis OK — SET/GET round-trip passed key={}", key);
            } else {
                log.error("[spike-verify] Redis FAILED — read-back mismatch: {}", readBack);
            }
        } catch (Exception e) {
            log.error("[spike-verify] Redis verification FAILED: {}", e.getMessage(), e);
        }
    }
}
