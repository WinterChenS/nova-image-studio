package com.nova.studio.rbac;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-25 (V5/T15) — {@code user_roles} access. The RBAC read path
 * (user_roles → role_permissions → permissions) is the permission source of
 * truth (R4); {@code users.role} remains the primary-role quick field and is
 * dual-written by the auth layer.
 */
@Repository
public class UserRoleRepository {

    private final UserRoleMapper mapper;

    public UserRoleRepository(UserRoleMapper mapper) {
        this.mapper = mapper;
    }

    /** Role codes for a user (empty rows → caller falls back to users.role). */
    public List<String> roleCodesForUser(UUID userId) {
        return mapper.roleCodesByUserId(userId);
    }

    /** Distinct permission codes for the given role ids. */
    public List<String> permissionCodesForRoles(List<UUID> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        return mapper.permissionCodesByRoleIds(roleIds);
    }

    /** Role id by code (admin|user); empty when the code is unknown. */
    public Optional<UUID> roleIdByCode(String code) {
        return Optional.ofNullable(mapper.roleIdByCode(code));
    }

    /** Replace the user's roles with a single role (本期单角色；多角色扩展 P2). */
    public void assignRole(UUID userId, String roleCode) {
        mapper.delete(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, userId));
        insertRole(userId, roleCode);
    }

    /** Insert a role row for a user (register path — no deletion). */
    public void insertRole(UUID userId, String roleCode) {
        roleIdByCode(roleCode).ifPresent(roleId -> {
            UserRoleEntity e = new UserRoleEntity();
            e.setUserId(userId);
            e.setRoleId(roleId);
            try {
                mapper.insert(e);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // 幂等：角色行已存在（V6 回填/并发注册）
            }
        });
    }

    /** Whether the user has any role rows (used for the users.role fallback decision). */
    public boolean hasRoles(UUID userId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<UserRoleEntity>()
                .eq(UserRoleEntity::getUserId, userId));
        return count != null && count > 0;
    }
}
