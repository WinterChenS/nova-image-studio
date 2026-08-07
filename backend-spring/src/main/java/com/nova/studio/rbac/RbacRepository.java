package com.nova.studio.rbac;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * T28 (WIN-29) — RBAC management access ({@code roles / permissions /
 * role_permissions}): role & permission code list for the matrix UI and the
 * transactional replace of a role's permission set (before/after resolution
 * stays in {@link RbacService}). All changes go through
 * {@link RbacService#updateRolePermissions} so the audit trail + cache
 * invalidation are never bypassed.
 */
@Repository
public class RbacRepository {

    private final JdbcTemplate jdbcTemplate;

    public RbacRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record RoleRow(UUID id, String code, String name, Boolean builtin,
                          Instant createdAt, Instant updatedAt) {
    }

    public record PermissionRow(UUID id, String code, String type, String parentCode,
                                String label, String apiPath, Integer sortOrder) {
    }

    public List<RoleRow> listRoles() {
        return jdbcTemplate.query("""
                SELECT id, code, name, builtin, created_at, updated_at
                FROM roles ORDER BY created_at ASC, code ASC
                """, (rs, i) -> new RoleRow(
                uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("builtin"),
                instant(rs, "created_at"), instant(rs, "updated_at")));
    }

    public List<PermissionRow> listPermissions() {
        return jdbcTemplate.query("""
                SELECT id, code, type, parent_code, label, api_path, sort_order
                FROM permissions ORDER BY sort_order ASC, code ASC
                """, (rs, i) -> new PermissionRow(
                uuid(rs, "id"), rs.getString("code"), rs.getString("type"), rs.getString("parent_code"),
                rs.getString("label"), rs.getString("api_path"), rs.getObject("sort_order") == null ? 0 : rs.getInt("sort_order")));
    }

    public Optional<RoleRow> findRoleById(UUID roleId) {
        List<RoleRow> rows = jdbcTemplate.query("""
                SELECT id, code, name, builtin, created_at, updated_at
                FROM roles WHERE id = ?
                """, (rs, i) -> new RoleRow(
                uuid(rs, "id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("builtin"),
                instant(rs, "created_at"), instant(rs, "updated_at")), roleId);
        return rows.stream().findFirst();
    }

    public List<UUID> permissionIdsForRole(UUID roleId) {
        return jdbcTemplate.query(
                "SELECT permission_id FROM role_permissions WHERE role_id = ? ORDER BY permission_id",
                (rs, i) -> uuid(rs, "permission_id"), roleId);
    }

    /** Replace a role's permission set atomically (delete + insert). */
    @Transactional
    public void replaceRolePermissions(UUID roleId, List<UUID> permissionIds) {
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
        if (permissionIds != null) {
            for (UUID permissionId : permissionIds) {
                jdbcTemplate.update("""
                        INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)
                        ON CONFLICT (role_id, permission_id) DO NOTHING
                        """, roleId, permissionId);
            }
        }
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : UUID.fromString(String.valueOf(value));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
