package com.nova.studio.rbac;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.infra.HttpErrorException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T28 (WIN-29) — RBAC management service: role list / permission code list /
 * role×permission matrix read + replace. Saving the matrix <b>always</b>:
 * <ol>
 *   <li>validates role + permission ids;</li>
 *   <li>atomically replaces {@code role_permissions};</li>
 *   <li>writes the before/after change to {@code audit_log} (A16);</li>
 *   <li>invalidates the permission cache globally (A14, ≤1s 生效).</li>
 * </ol>
 */
@Service
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    private final RbacRepository repository;
    private final AuditLogService auditLogService;
    private final UserPermissionService permissionService;

    public RbacService(RbacRepository repository,
                       AuditLogService auditLogService,
                       UserPermissionService permissionService) {
        this.repository = repository;
        this.auditLogService = auditLogService;
        this.permissionService = permissionService;
    }

    public List<Map<String, Object>> listRoles() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RbacRepository.RoleRow role : repository.listRoles()) {
            Map<String, Object> dto = new LinkedHashMap<>();
            dto.put("id", role.id().toString());
            dto.put("code", role.code());
            dto.put("name", role.name());
            dto.put("builtin", role.builtin());
            result.add(dto);
        }
        return result;
    }

    /** 权限码清单（矩阵界面 + 防漂移单测数据源）。 */
    public List<Map<String, Object>> listPermissions() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (RbacRepository.PermissionRow p : repository.listPermissions()) {
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

    public Map<String, Object> getRolePermissions(UUID roleId) {
        RbacRepository.RoleRow role = requireRole(roleId);
        List<UUID> ids = repository.permissionIdsForRole(roleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("roleId", roleId.toString());
        result.put("roleCode", role.code());
        result.put("permissionIds", ids.stream().map(UUID::toString).toList());
        return result;
    }

    /**
     * Replace a role's permission set. Writes audit_log with before/after
     * permission codes (A16) and invalidates the permission cache (A14).
     */
    public Map<String, Object> updateRolePermissions(UUID actorId, UUID roleId, List<UUID> permissionIds) {
        RbacRepository.RoleRow role = requireRole(roleId);
        List<UUID> cleanIds = permissionIds == null ? List.of() : permissionIds.stream().distinct().toList();
        Map<UUID, String> codeById = permissionCodeById();
        for (UUID pid : cleanIds) {
            if (!codeById.containsKey(pid)) {
                throw new HttpErrorException(400, "UNKNOWN_PERMISSION", "权限码不存在: " + pid);
            }
        }

        List<String> before = repository.permissionIdsForRole(roleId).stream().map(codeById::get).toList();
        repository.replaceRolePermissions(roleId, cleanIds);
        List<String> after = cleanIds.stream().map(codeById::get).toList();

        auditLogService.log(actorId, "role_permissions.update", "role_permissions", roleId.toString(),
                Map.of("role", role.code(), "before", before, "after", after));
        permissionService.invalidateAll();   // A14: 矩阵变更 ≤1s 生效
        log.info("[rbac] 角色 {} 权限矩阵更新：{} → {}", role.code(), before, after);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("roleId", roleId.toString());
        result.put("before", before);
        result.put("after", after);
        return result;
    }

    private RbacRepository.RoleRow requireRole(UUID roleId) {
        return repository.findRoleById(roleId)
                .orElseThrow(() -> new HttpErrorException(404, "ROLE_NOT_FOUND", "角色不存在"));
    }

    private Map<UUID, String> permissionCodeById() {
        Map<UUID, String> map = new LinkedHashMap<>();
        for (RbacRepository.PermissionRow p : repository.listPermissions()) {
            map.put(p.id(), p.code());
        }
        return map;
    }
}
