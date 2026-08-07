package com.nova.studio.rbac;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nova.studio.auth.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * WIN-25 (T14, ADR-29) — 权限判定唯一入口（R4）：读
 * {@code user_roles → role_permissions → permissions} 得出 {roles, permissions}；
 * user_roles 为空时回退 {@code users.role}（V6 回填兜底）。结果入 Caffeine
 * 缓存（TTL ≤1s + 角色指派显式失效，A14「矩阵变更即时生效」）。
 *
 * <p>JWT 只带 role；每请求经 {@link com.nova.studio.auth.JwtAuthenticationFilter}
 * 从这里加载权限码注入 {@code PERM_} authorities。
 */
@Service
public class UserPermissionService {

    private static final Logger log = LoggerFactory.getLogger(UserPermissionService.class);

    /** {roles, permissions} for a user (permission 判定依据，A13/A14). */
    public record UserPermissions(List<String> roles, List<String> permissions) {
    }

    private final Cache<UUID, UserPermissions> cache;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;

    public UserPermissionService(UserRoleRepository userRoleRepository, UserRepository userRepository) {
        this.userRoleRepository = userRoleRepository;
        this.userRepository = userRepository;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(1))   // ADR-29: ≤1s 失效
                .maximumSize(10_000)
                .build();
    }

    /** Cached read — the single permission-loading entry point (G.2). */
    public UserPermissions load(UUID userId) {
        return cache.get(userId, this::loadFromDb);
    }

    /** Invalidate one user (role assignment / user_roles change, T15). */
    public void invalidate(UUID userId) {
        cache.invalidate(userId);
    }

    /** Invalidate everyone (role-permission matrix change, P1 T28). */
    public void invalidateAll() {
        cache.invalidateAll();
    }

    private UserPermissions loadFromDb(UUID userId) {
        List<String> roleCodes = new ArrayList<>(userRoleRepository.roleCodesForUser(userId));
        if (roleCodes.isEmpty()) {
            // V6 回填兜底：user_roles 无行时按 users.role 主角色快捷字段
            userRepository.findById(userId).ifPresent(row -> {
                if (row.role() != null && !row.role().isBlank()) {
                    roleCodes.add(row.role());
                }
            });
        }
        List<String> permissionCodes = new ArrayList<>();
        for (String roleCode : roleCodes) {
            userRoleRepository.roleIdByCode(roleCode)
                    .ifPresent(roleId -> permissionCodes.addAll(userRoleRepository.permissionCodesForRoles(List.of(roleId))));
        }
        List<String> distinctPermissions = permissionCodes.stream()
                .distinct().sorted(Comparator.naturalOrder()).toList();
        List<String> distinctRoles = roleCodes.stream().distinct().sorted().toList();
        log.debug("[rbac] user {} roles={} permissions={}", userId, distinctRoles, distinctPermissions.size());
        return new UserPermissions(distinctRoles, distinctPermissions);
    }
}
