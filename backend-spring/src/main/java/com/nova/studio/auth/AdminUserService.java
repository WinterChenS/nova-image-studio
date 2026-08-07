package com.nova.studio.auth;

import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.rbac.UserPermissionService;
import com.nova.studio.rbac.UserRoleRepository;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * WIN-22 (F-40..F-42 / D.4 / Q4) — admin-only user management: list, enable/
 * disable ({@code status}), grant/revoke admin ({@code role}) and admin-set
 * password ({@code reset-password}, bcrypt; email delivery stays P2).
 *
 * <p>Guards (R-8/G): an admin cannot disable or demote themselves, and cannot
 * disable/demote the last remaining admin (would lock the instance out).
 *
 * <p>WIN-25 (T15, R4) — 角色指派双写 {@code user_roles} + 失效权限缓存（A14）。
 */
@Service
public class AdminUserService {

    private static final Pattern PASSWORD_PATTERN = Pattern.compile("^.{6,72}$");

    private final UserRepository repository;
    private final UserRoleRepository userRoleRepository;
    private final UserPermissionService permissionService;

    public AdminUserService(UserRepository repository, UserRoleRepository userRoleRepository,
                            UserPermissionService permissionService) {
        this.repository = repository;
        this.userRoleRepository = userRoleRepository;
        this.permissionService = permissionService;
    }

    /** All users (id/username/role/status/createdAt/lastLoginAt), newest first. */
    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserRepository.UserRow row : repository.listAll()) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", row.id().toString());
            user.put("username", row.username());
            user.put("role", row.role());
            user.put("status", row.status() == null ? "active" : row.status());
            user.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
            user.put("lastLoginAt", row.lastLoginAt() == null ? null : row.lastLoginAt().toString());
            result.add(user);
        }
        return result;
    }

    /** Update status / role with the self + last-admin guards. */
    public Map<String, Object> update(UUID operatorId, UUID targetId, String status, String role) {
        UserRepository.UserRow target = repository.findById(targetId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "用户不存在"));
        boolean demoting = role != null && !role.equals(target.role()) && "admin".equals(target.role());
        boolean disabling = status != null && "disabled".equals(status) && !"disabled".equals(target.status());

        if (targetId.equals(operatorId) && (demoting || disabling)) {
            throw new HttpErrorException(400, "SELF_OPERATION", "不能禁用或降级自己的账号");
        }
        if ((demoting || disabling) && "admin".equals(target.role())) {
            long adminCount = repository.countByRole("admin");
            if (adminCount <= 1) {
                throw new HttpErrorException(400, "LAST_ADMIN", "不能禁用或降级最后一名管理员");
            }
        }
        if (role != null) {
            if (!"admin".equals(role) && !"user".equals(role)) {
                throw new IllegalArgumentException("角色无效");
            }
            repository.updateRole(targetId, role);
            // T15 (R4): 双写 user_roles + 失效权限缓存（A14 即时生效）
            userRoleRepository.assignRole(targetId, role);
            permissionService.invalidate(targetId);
        }
        if (status != null) {
            if (!"active".equals(status) && !"disabled".equals(status)) {
                throw new IllegalArgumentException("状态无效");
            }
            repository.updateStatus(targetId, status);
        }
        return toUserJson(target);
    }

    /** Admin-set password (Q4): new bcrypt hash, no email (P2). */
    public void resetPassword(UUID targetId, String newPassword) {
        if (newPassword == null || !PASSWORD_PATTERN.matcher(newPassword).matches()) {
            throw new IllegalArgumentException("密码长度至少 6 位且不超过 72 位");
        }
        if (!repository.findById(targetId).isPresent()) {
            throw new HttpErrorException(404, "NOT_FOUND", "用户不存在");
        }
        repository.updatePasswordHash(targetId, new BCryptPasswordEncoder(12).encode(newPassword));
    }

    private static Map<String, Object> toUserJson(UserRepository.UserRow row) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", row.id().toString());
        user.put("username", row.username());
        user.put("role", row.role());
        user.put("status", row.status() == null ? "active" : row.status());
        user.put("createdAt", row.createdAt() == null ? null : row.createdAt().toString());
        user.put("lastLoginAt", row.lastLoginAt() == null ? null : row.lastLoginAt().toString());
        return user;
    }
}
