package com.nova.studio.web;

import com.nova.studio.auth.AdminUserService;
import com.nova.studio.auth.AuthSupport;
import com.nova.studio.auth.AuthUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * WIN-22 (D.4) — admin-only user management API (F-40..F-42):
 * {@code GET /api/nova/admin/users}, {@code PATCH /api/nova/admin/users/{id}}
 * (status/role), {@code POST /api/nova/admin/users/{id}/reset-password} (Q4).
 * Non-admin → 403 (401 anonymous), enforced in the controller.
 */
@RestController
@PreAuthorize("hasAuthority('PERM_user.manage')")
@RequestMapping("/api/nova/admin/users")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        return adminUserService.listUsers();
    }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody JsonNode body,
                                      @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        String status = body.hasNonNull("status") ? body.get("status").asText() : null;
        String role = body.hasNonNull("role") ? body.get("role").asText() : null;
        return adminUserService.update(authUser.id(), id, status, role);
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Object> resetPassword(@PathVariable UUID id, @RequestBody JsonNode body,
                                             @AuthenticationPrincipal AuthUser authUser) {
        AuthSupport.requireAdmin(authUser);
        String password = body.hasNonNull("password") ? body.get("password").asText() : null;
        adminUserService.resetPassword(id, password);
        return Map.of("ok", true);
    }
}
