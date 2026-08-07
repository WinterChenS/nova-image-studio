package com.nova.studio.rbac;

import com.nova.studio.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T28 (WIN-29) — RBAC admin service (A16): roles + permissions listing for the
 * matrix UI, and role-permission replacement that (a) validates permission
 * codes exist, (b) writes the change to {@code audit_log} and (c) invalidates
 * the permission cache so changes apply within ≤1s (A14).
 */
class RbacAdminServiceTest {

    private RolePermissionRepository repository;
    private UserPermissionService permissionService;
    private AuditLogService auditLog;
    private RbacAdminService service;

    private static final UUID ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ACTOR = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID PERM_1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID PERM_2 = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @BeforeEach
    void setUp() {
        repository = mock(RolePermissionRepository.class);
        permissionService = mock(UserPermissionService.class);
        auditLog = mock(AuditLogService.class);
        service = new RbacAdminService(repository, permissionService, auditLog);
    }

    @Test
    void replaceRolePermissionsWritesAuditAndInvalidatesCache() {
        when(repository.findRole(ROLE_ID)).thenReturn(Optional.of(
                new RolePermissionRepository.RoleRow(ROLE_ID, "admin", "管理员", true)));
        when(repository.findPermissionIdsByCodes(List.of("audit.view", "pricing.manage")))
                .thenReturn(List.of(PERM_1, PERM_2));
        when(repository.permissionCodesForRole(ROLE_ID)).thenReturn(List.of("workbench.view"));

        service.replaceRolePermissions(ACTOR, ROLE_ID, List.of("audit.view", "pricing.manage"));

        verify(repository).replaceRolePermissions(ROLE_ID, List.of(PERM_1, PERM_2));
        verify(auditLog).record(any(), anyString(), anyString(), anyString(), any());
        verify(permissionService).invalidateAll();
    }

    @Test
    void unknownPermissionCodeRejected() {
        when(repository.findRole(ROLE_ID)).thenReturn(Optional.of(
                new RolePermissionRepository.RoleRow(ROLE_ID, "admin", "管理员", true)));
        when(repository.findPermissionIdsByCodes(List.of("no.such.perm"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.replaceRolePermissions(ACTOR, ROLE_ID, List.of("no.such.perm")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).replaceRolePermissions(any(), any());
    }

    @Test
    void missingRoleRejected() {
        when(repository.findRole(ROLE_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.replaceRolePermissions(ACTOR, ROLE_ID, List.of("audit.view")))
                .hasMessageContaining("角色不存在");
    }

    @Test
    void roleListingIncludesPermissionCodes() {
        when(repository.listRoles()).thenReturn(List.of(
                new RolePermissionRepository.RoleRow(ROLE_ID, "admin", "管理员", true)));
        when(repository.permissionCodesForRole(ROLE_ID)).thenReturn(List.of("workbench.view", "usage.me"));
        var roles = service.listRoles();
        assertThat(roles).hasSize(1);
        assertThat(roles.get(0).get("permissions")).isEqualTo(List.of("workbench.view", "usage.me"));
    }
}
