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
 * T1.1 — Flyway + PostgreSQL connectivity against the external server.
 *
 * <p>Runs only when {@code DB_HOST} is set (e.g. exported from {@code .env} by
 * the run script); skipped in CI without the external server. Verifies the M1
 * DDL (tasks/task_items in the public schema of the user-confirmed {@code nova}
 * database).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class FlywayPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayAppliedV1MigrationInPublicSchema() {
        List<Map<String, Object>> migrations = jdbcTemplate.queryForList(
                "SELECT version, description, success FROM public.flyway_schema_history "
                        + "WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(migrations).isNotEmpty();
        Map<String, Object> v1 = migrations.get(0);
        assertThat(v1.get("version").toString()).isEqualTo("1");
        assertThat(v1.get("success")).isEqualTo(true);
    }

    @Test
    void taskTablesExistAndAreUsable() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('tasks', 'task_items')
                """, Integer.class);
        assertThat(tableCount).isEqualTo(2);

        // Insert + read + delete round-trip on the public schema.
        String id = java.util.UUID.randomUUID().toString();
        String now = java.time.Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO tasks (id, user_id, status, mode, request_json, created_at)
                VALUES (?, NULL, '排队中', 'text-to-image', ?::jsonb, ?::timestamptz)
                """, id, "{\"prompt\":\"x\"}", now);
        jdbcTemplate.update("""
                INSERT INTO task_items (task_id, item_index, status, created_at)
                VALUES (?, 0, '排队中', ?::timestamptz)
                """, id, now);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tasks WHERE id = ?", String.class, id);
        assertThat(status).isEqualTo("排队中");
        jdbcTemplate.update("DELETE FROM tasks WHERE id = ?", id);
    }

    @Test
    void currentDatabaseIsPostgres() {
        String product = jdbcTemplate.queryForObject("SELECT version()", String.class);
        assertThat(product.toLowerCase()).contains("postgresql");
    }
}
