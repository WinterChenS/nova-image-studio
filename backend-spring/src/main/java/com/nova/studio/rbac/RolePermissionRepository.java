package com.nova.studio.rbac;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-29 (V5/T28) — roles / permissions / role_permissions admin access for
 * the "角色与权限" matrix UI (rbac.manage): role listing (with permission
 * codes), permission catalog, and role-permission replacement (audit +
 * cache-invalidation handled by {@link RbacAdminService}).
 */
@Repository
public class RolePermissionRepository {

    /** Public role row for the matrix UI. */
    public record RoleRow(UUID id, String code, String name, Boolean builtin) {
    }

    /** Public permission row (权限码清单). */
    public record PermissionRow(UUID id, String code, String type, String parentCode,
                                String label, String apiPath, Integer sortOrder) {
    }

    private final RoleMapper roleMapper;
    private final PermissionMapper permissionMapper;
    private final RolePermissionMapper rolePermissionMapper;

    public RolePermissionRepository(RoleMapper roleMapper,
                                    PermissionMapper permissionMapper,
                                    RolePermissionMapper rolePermissionMapper) {
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
        this.rolePermissionMapper = rolePermissionMapper;
    }

    public List<RoleRow> listRoles() {
        return roleMapper.selectList(new LambdaQueryWrapper<RoleEntity>()
                        .orderByAsc(RoleEntity::getCode)).stream()
                .map(r -> new RoleRow(r.getId(), r.getCode(), r.getName(), r.getBuiltin()))
                .toList();
    }

    public Optional<RoleRow> findRole(UUID roleId) {
        return Optional.ofNullable(roleMapper.selectById(roleId))
                .map(r -> new RoleRow(r.getId(), r.getCode(), r.getName(), r.getBuiltin()));
    }

    public List<PermissionRow> listPermissions() {
        return permissionMapper.selectList(new LambdaQueryWrapper<PermissionEntity>()
                        .orderByAsc(PermissionEntity::getSortOrder)).stream()
                .map(p -> new PermissionRow(p.getId(), p.getCode(), p.getType(), p.getParentCode(),
                        p.getLabel(), p.getApiPath(), p.getSortOrder()))
                .toList();
    }

    /** Permission ids for the given codes (unknown codes are dropped — caller validates). */
    public List<UUID> findPermissionIdsByCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        return permissionMapper.selectList(new LambdaQueryWrapper<PermissionEntity>()
                        .in(PermissionEntity::getCode, codes)).stream()
                .map(PermissionEntity::getId)
                .toList();
    }

    /** Permission codes granted to a role (for the matrix checkboxes). */
    public List<String> permissionCodesForRole(UUID roleId) {
        return rolePermissionMapper.permissionCodesByRoleId(roleId);
    }

    /** Replace a role's permission set (delete-then-insert in one transaction-ish sequence). */
    public void replaceRolePermissions(UUID roleId, List<UUID> permissionIds) {
        rolePermissionMapper.delete(new LambdaQueryWrapper<RolePermissionEntity>()
                .eq(RolePermissionEntity::getRoleId, roleId));
        for (UUID permissionId : permissionIds) {
            RolePermissionEntity e = new RolePermissionEntity();
            e.setRoleId(roleId);
            e.setPermissionId(permissionId);
            rolePermissionMapper.insert(e);
        }
    }
}
