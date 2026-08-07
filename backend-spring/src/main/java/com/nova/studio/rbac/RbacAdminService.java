package com.nova.studio.rbac;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.infra.HttpErrorException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T28 (WIN-29) — RBAC admin service (A16): roles + permissions listing for the
 * "角色与权限" matrix UI, and role-permission replacement that (a) validates
 * permission codes exist, (b) writes the change to {@code audit_log} and (c)
 * invalidates the permission cache so changes apply within ≤1s (A14, no
 * re-login required).
 */
@Service
public class RbacAdminService {

    private final RolePermissionRepository repository;
    private final UserPermissionService permissionService;
    private final AuditLogService auditLog;

    public RbacAdminService(RolePermissionRepository repository,
                            UserPermissionService permissionService,
                            AuditLogService auditLog) {
        this.repository = repository;
        this.permissionService = permissionService;
        this.auditLog = auditLog;
    }

    /** Roles with their current permission codes (matrix rows). */
    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RolePermissionRepository.RoleRow role : repository.listRoles()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", role.id().toString());
            dto.put("code", role.code());
            dto.put("name", role.name());
            dto.put("builtin", role.builtin());
            dto.put("permissions", repository.permissionCodesForRole(role.id()));
            result.add(dto);
        }
        return result;
    }

    /** 权限码清单（type/parent/label/apiPath for the matrix + 防漂移映射来源）. */
    public List<Map<String, Object>> listPermissions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RolePermissionRepository.PermissionRow p : repository.listPermissions()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", p.id().toString());
            dto.put("code", p.code());
            dto.put("type", p.type());
            dto.put("parentCode", p.parentCode());
            dto.put("label", p.label());
            dto.put("apiPath", p.apiPath());
            dto.put("sortOrder", p.sortOrder());
            result.add(dto);
        }
        return result;
    }

    /**
     * Replace a role's permission set. Writes audit_log (before → after code
     * sets) and invalidates the whole permission cache (A14 ≤1s).
     */
    public Map<String, Object> replaceRolePermissions(UUID actorId, UUID roleId, List<String> permissionCodes) {
        RolePermissionRepository.RoleRow role = repository.findRole(roleId)
                .orElseThrow(() -> new HttpErrorException(404, "NOT_FOUND", "角色不存在"));
        List<String> codes = permissionCodes == null ? List.of()
                : permissionCodes.stream().filter(c -> c != null && !c.isBlank()).distinct().toList();

        // 校验权限码全部存在（防漂移：矩阵只能勾选已注册权限码）
        List<UUID> permissionIds = repository.findPermissionIdsByCodes(codes);
        if (permissionIds.size() != codes.size()) {
            throw new IllegalArgumentException("包含不存在的权限码");
        }
        List<String> before = repository.permissionCodesForRole(roleId);
        repository.replaceRolePermissions(roleId, permissionIds);

        // A16: 权限变更落审计日志（before → after）
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("role", role.code());
        detail.put("before", before);
        detail.put("after", codes);
        auditLog.record(actorId, "role_permissions.update", "role_permissions", roleId.toString(), detail);

        // A14: 全局失效权限缓存（≤1s 内矩阵变更生效）
        permissionService.invalidateAll();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("roleId", roleId.toString());
        result.put("permissions", codes);
        return result;
    }
}
