package com.nova.studio.rbac;

import com.nova.studio.audit.AuditLogService;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T28 (WIN-29) — RBAC management service: role list / permission code list /
 * role×permission matrix read + replace. Saving the matrix writes audit_log
 * (A16) and invalidates the permission cache (A14, ≤1s 生效).
 */
class RbacServiceTest {

    private RbacRepository repository;
    private AuditLogService auditLogService;
    private UserPermissionService permissionService;
    private RbacService service;

    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001"); // admin
    private static final UUID PERM_1 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID PERM_2 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");
    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        repository = mock(RbacRepository.class);
        auditLogService = mock(AuditLogService.class);
        permissionService = mock(UserPermissionService.class);
        service = new RbacService(repository, auditLogService, permissionService);
    }

    private RbacRepository.RoleRow roleRow() {
        return new RbacRepository.RoleRow(ROLE_ID, "admin", "管理员", true,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void updateRolePermissionsReplacesMatrixAndWritesAuditLog() {
        when(repository.findRoleById(ROLE_ID)).thenReturn(Optional.of(roleRow()));
        when(repository.permissionIdsForRole(ROLE_ID)).thenReturn(List.of(PERM_1));
        when(repository.listPermissions()).thenReturn(List.of(
                new RbacRepository.PermissionRow(PERM_1, "workbench.view", "menu", null, "生图工作台", null, 10),
                new RbacRepository.PermissionRow(PERM_2, "usage.me", "menu", null, "我的用量", "/api/nova/usage/me", 20)));

        var result = service.updateRolePermissions(ACTOR, ROLE_ID, List.of(PERM_1, PERM_2));

        verify(repository).replaceRolePermissions(ROLE_ID, List.of(PERM_1, PERM_2));
        assertThat(result.get("before")).isEqualTo(List.of("workbench.view"));
        assertThat(result.get("after")).isEqualTo(List.of("workbench.view", "usage.me"));
        // A16: 矩阵变更落审计日志（before/after 权限码）
        org.mockito.ArgumentCaptor<java.util.Map<String, Object>> detail =
                org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditLogService).log(eq(ACTOR), eq("role_permissions.update"), eq("role_permissions"),
                eq(ROLE_ID.toString()), detail.capture());
        assertThat(detail.getValue()).containsEntry("role", "admin");
        // A14: 权限缓存全局失效（≤1s 语义）
        verify(permissionService).invalidateAll();
    }

    @Test
    void updateUnknownRoleReturns404() {
        when(repository.findRoleById(ROLE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateRolePermissions(ACTOR, ROLE_ID, List.of(PERM_1)))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(404));
        verify(repository, never()).replaceRolePermissions(any(), any());
    }

    @Test
    void updateWithUnknownPermissionRejects() {
        when(repository.findRoleById(ROLE_ID)).thenReturn(Optional.of(roleRow()));
        when(repository.listPermissions()).thenReturn(List.of(
                new RbacRepository.PermissionRow(PERM_1, "workbench.view", "menu", null, "生图工作台", null, 10)));
        UUID ghost = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
        assertThatThrownBy(() -> service.updateRolePermissions(ACTOR, ROLE_ID, List.of(PERM_1, ghost)))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(400));
        verify(repository, never()).replaceRolePermissions(any(), any());
    }

    @Test
    void listRolesAndPermissionsPassthrough() {
        when(repository.listRoles()).thenReturn(List.of(roleRow()));
        when(repository.listPermissions()).thenReturn(List.of());
        assertThat(service.listRoles()).hasSize(1);
        assertThat(service.listPermissions()).isEmpty();
    }
}
