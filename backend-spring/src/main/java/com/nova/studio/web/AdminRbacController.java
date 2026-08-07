package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.rbac.RbacService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * T28 (WIN-29) — RBAC 管理 API {@code /api/nova/admin/roles**} (A16):
 * <ul>
 *   <li>GET  /roles — 角色列表（code/name/builtin）</li>
 *   <li>GET  /roles/permissions — 权限码清单（矩阵界面数据源）</li>
 *   <li>GET  /roles/{roleId}/permissions — 角色已授权限 id</li>
 *   <li>PUT  /roles/{roleId}/permissions — 保存矩阵（写 audit_log + 缓存失效）</li>
 * </ul>
 * 权限 {@code PERM_rbac.manage}（V6 种子 api_path=/api/nova/admin/roles 前缀覆盖）。
 * 所有端点挂 {@code /api/nova/admin/roles} 前缀以满足防漂移单测（G.2 ②）。
 */
@RestController
@PreAuthorize("hasAuthority('PERM_rbac.manage')")
@RequestMapping("/api/nova/admin/roles")
public class AdminRbacController {

    private final RbacService rbacService;

    public AdminRbacController(RbacService rbacService) {
        this.rbacService = rbacService;
    }

    @GetMapping
    public List<Map<String, Object>> listRoles(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return rbacService.listRoles();
    }

    /** 权限码清单（字面路径优先于 {roleId} 匹配）。 */
    @GetMapping("/permissions")
    public List<Map<String, Object>> listPermissions(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return rbacService.listPermissions();
    }

    @GetMapping("/{roleId}/permissions")
    public Map<String, Object> getRolePermissions(@PathVariable UUID roleId,
                                                  @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return rbacService.getRolePermissions(roleId);
    }

    @PutMapping("/{roleId}/permissions")
    public Map<String, Object> updatePermissions(@PathVariable UUID roleId, @RequestBody JsonNode body,
                                                 @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        List<UUID> permissionIds = new ArrayList<>();
        if (body != null && body.has("permissionIds") && body.get("permissionIds").isArray()) {
            for (JsonNode item : body.get("permissionIds")) {
                if (item.isTextual()) {
                    try {
                        permissionIds.add(UUID.fromString(item.asText()));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("permissionIds 包含无效的 UUID");
                    }
                }
            }
        }
        return rbacService.updateRolePermissions(admin.id(), roleId, permissionIds);
    }
}
