package com.nova.studio.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0.1 — Flyway + PostgreSQL connectivity against the external server.
 *
 * <p>Runs only when {@code DB_HOST} is set (e.g. exported from {@code .env} by the
 * run script); skipped in CI without the external server. Creates the
 * {@code nova_spike} schema in the shared {@code postgres} DB (approved for M0).
 */
@SpringBootTest(properties = {
        "nova.ai.openai.enabled=false",
        "nova.spike.verify=false"
})
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class FlywayPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedMigrationAndTableUsable() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM nova_spike.flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(migrations).isNotEmpty();
        Map<String, Object> v1 = migrations.get(0);
        assertThat(v1.get("version").toString()).isEqualTo("1");
        assertThat(v1.get("success")).isEqualTo(true);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, note FROM nova_spike.spike_probe ORDER BY id");
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("note").toString()).isEqualTo("M0 spike flyway ok");
    }

    @Test
    void currentDatabaseIsPostgres() {
        String product = jdbcTemplate.queryForObject("SELECT version()", String.class);
        assertThat(product.toLowerCase()).contains("postgresql");
    }
}
