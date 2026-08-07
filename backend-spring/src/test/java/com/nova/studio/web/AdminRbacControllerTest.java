package com.nova.studio.web;

import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.rbac.RbacAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T28 (WIN-29) — /api/nova/admin/roles authorization (A1/A16: admin only via
 * PERM_rbac.manage; 普通用户 403) and delegation to {@link RbacAdminService}.
 */
class AdminRbacControllerTest {

    private final RbacAdminService service = mock(RbacAdminService.class);
    private AdminRbacController controller;

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "boss", "admin");
    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new AdminRbacController(service);
    }

    @Test
    void anonymousRejectedOnRoles() {
        assertThatThrownBy(() -> controller.listRoles(null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void nonAdminRejectedOnRoles() {
        assertThatThrownBy(() -> controller.listRoles(user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
        verify(service, never()).listRoles();
    }

    @Test
    void adminListsRoles() {
        when(service.listRoles()).thenReturn(List.of(Map.of("code", "admin")));
        assertThat(controller.listRoles(admin)).hasSize(1);
    }

    @Test
    void nonAdminRejectedOnPermissionList() {
        assertThatThrownBy(() -> controller.listPermissions(user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }

    @Test
    void adminListsPermissions() {
        when(service.listPermissions()).thenReturn(List.of(Map.of("code", "workbench.view")));
        assertThat(controller.listPermissions(admin)).hasSize(1);
    }

    @Test
    void replaceRolePermissionsRequiresAdmin() {
        assertThatThrownBy(() -> controller.replaceRolePermissions(UUID.randomUUID(), jsonBody(List.of("audit.view")), user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
        verify(service, never()).replaceRolePermissions(any(), any(), any());
    }

    @Test
    void adminReplacesRolePermissions() {
        when(service.replaceRolePermissions(any(), any(), any())).thenReturn(Map.of("ok", true));
        assertThat(controller.replaceRolePermissions(UUID.randomUUID(),
                jsonBody(List.of("audit.view")), admin)).containsEntry("ok", true);
    }

    private static tools.jackson.databind.JsonNode jsonBody(List<String> codes) {
        var node = new tools.jackson.databind.node.ObjectNode(new tools.jackson.databind.node.JsonNodeFactory());
        var arr = node.putArray("permissionCodes");
        codes.forEach(arr::add);
        return node;
    }
}
