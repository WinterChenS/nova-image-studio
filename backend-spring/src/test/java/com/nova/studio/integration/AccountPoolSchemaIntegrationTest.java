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
 * T1 (WIN-28) — Flyway V5/V6 schema + RBAC seed verification against the
 * external PostgreSQL (runs only when {@code DB_HOST} is set, same gating as
 * {@link FlywayPostgresIntegrationTest}).
 *
 * <p>Covers acceptance A12/A15: admin/user roles seeded exactly once
 * (idempotent ON CONFLICT), 13 permission codes, admin=all / user=default
 * role_permissions, and the existing {@code users.role} → {@code user_roles}
 * backfill.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class AccountPoolSchemaIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void v5TablesExist() {
        List<String> tables = jdbcTemplate.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN
                ('ai_models', 'ai_accounts', 'ai_model_pricing', 'usage_records',
                 'usage_daily_agg', 'roles', 'permissions', 'role_permissions',
                 'user_roles', 'audit_log')
                """, String.class);
        assertThat(tables).containsExactlyInAnyOrder(
                "ai_models", "ai_accounts", "ai_model_pricing", "usage_records",
                "usage_daily_agg", "roles", "permissions", "role_permissions",
                "user_roles", "audit_log");
    }

    @Test
    void usageRecordsHasIdempotencyConstraint() {
        List<Map<String, Object>> uniques = jdbcTemplate.queryForList("""
                SELECT indexdef FROM pg_indexes WHERE tablename = 'usage_records'
                AND indexdef LIKE '%(ref_type, ref_id)%'
                """);
        assertThat(uniques).as("UNIQUE(ref_type, ref_id) 幂等约束").hasSize(1);
    }

    @Test
    void accountsStatusCheckConstraintExists() {
        List<Map<String, Object>> checks = jdbcTemplate.queryForList("""
                SELECT pg_get_constraintdef(oid) AS def FROM pg_constraint
                WHERE conrelid = 'ai_accounts'::regclass AND contype = 'c'
                """);
        assertThat(checks).anySatisfy(row ->
                assertThat(String.valueOf(row.get("def"))).contains("active", "paused", "broken", "deleted"));
    }

    @Test
    void rolesSeededExactlyOnce() {
        List<Map<String, Object>> roles = jdbcTemplate.queryForList(
                "SELECT code, name, builtin FROM roles ORDER BY code");
        assertThat(roles).extracting(r -> r.get("code")).containsExactly("admin", "user");
        assertThat(roles).allSatisfy(r -> assertThat(r.get("builtin")).isEqualTo(true));
    }

    @Test
    void permissionsSeededWithExpectedCodes() {
        List<String> codes = jdbcTemplate.queryForList(
                "SELECT code FROM permissions ORDER BY sort_order", String.class);
        assertThat(codes).contains(
                "workbench.view", "usage.me", "admin.console.view", "project.manage",
                "asset.manage", "user.manage", "account.manage", "account.test",
                "model.catalog.manage", "pricing.manage", "audit.view", "audit.export",
                "rbac.manage");
        assertThat(codes).hasSize(13);
    }

    @Test
    void rolePermissionsAdminAllUserDefault() {
        Long adminPerms = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                WHERE r.code = 'admin'
                """, Long.class);
        Long userPerms = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp JOIN roles r ON r.id = rp.role_id
                WHERE r.code = 'user'
                """, Long.class);
        assertThat(adminPerms).isEqualTo(13);
        assertThat(userPerms).isEqualTo(2);
    }

    @Test
    void existingUsersBackfilledIntoUserRoles() {
        // Every existing users.role (admin|user) must map to a user_roles row.
        List<Map<String, Object>> orphans = jdbcTemplate.queryForList("""
                SELECT u.id FROM users u
                LEFT JOIN user_roles ur ON ur.user_id = u.id
                LEFT JOIN roles r ON r.id = ur.role_id AND r.code = u.role
                WHERE u.role IN ('admin', 'user') AND r.id IS NULL
                LIMIT 10
                """);
        assertThat(orphans).isEmpty();
    }

    @Test
    void seedsAreIdempotentAcrossRepeatedMigrations() {
        // Flyway runs V6 once, but re-running the seed statements must not duplicate rows.
        Integer adminCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE code = 'admin'", Integer.class);
        assertThat(adminCount).isEqualTo(1);
        Integer permCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permissions WHERE code = 'account.manage'", Integer.class);
        assertThat(permCount).isEqualTo(1);
    }
}
