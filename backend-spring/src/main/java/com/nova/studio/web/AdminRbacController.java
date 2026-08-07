package com.nova.studio.web;

import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.rbac.RbacAdminService;
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
 * T28 (WIN-29) — RBAC admin API {@code /api/nova/admin/roles} +
 * {@code /api/nova/admin/permissions} (A16): role × permission matrix UI data
 * source + role-permission replacement (written to audit_log, cache
 * invalidated ≤1s — A14). Requires PERM_rbac.manage (P1 seed, admin default).
 */
@RestController
@PreAuthorize("hasAuthority('PERM_rbac.manage')")
@RequestMapping("/api/nova/admin")
public class AdminRbacController {

    private final RbacAdminService rbacService;

    public AdminRbacController(RbacAdminService rbacService) {
        this.rbacService = rbacService;
    }

    /** 角色列表（含各自权限码，矩阵行）。 */
    @GetMapping("/roles")
    public List<Map<String, Object>> listRoles(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return rbacService.listRoles();
    }

    /** 权限码清单（矩阵列 + 前端清单来源）。 */
    @GetMapping("/permissions")
    public List<Map<String, Object>> listPermissions(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return rbacService.listPermissions();
    }

    /** 保存角色权限集：写 audit_log + 全局失效权限缓存（A14/A16）。 */
    @PutMapping("/roles/{roleId}/permissions")
    public Map<String, Object> replaceRolePermissions(@PathVariable UUID roleId,
                                                      @RequestBody JsonNode body,
                                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthUser admin = AuthSupport.requireAdmin(authUser);
        List<String> codes = new ArrayList<>();
        JsonNode node = body == null ? null : body.get("permissionCodes");
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual()) {
                    codes.add(item.asText());
                }
            }
        }
        return rbacService.replaceRolePermissions(admin.id(), roleId, codes);
    }
}
