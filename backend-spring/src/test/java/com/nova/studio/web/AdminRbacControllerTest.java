package com.nova.studio.web;

import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.rbac.RbacService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
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
 * T28 (WIN-29) — {@code /api/nova/admin/roles**} authorization + delegation
 * (A13): 401 anonymous / 403 non-admin / admin passthrough. Method security
 * ({@code PERM_rbac.manage}) enforced by the filter chain; the drift test
 * guarantees the seed api_path covers every endpoint under this prefix.
 */
class AdminRbacControllerTest {

    private final RbacService service = mock(RbacService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AdminRbacController controller;

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "boss", "admin");
    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new AdminRbacController(service);
    }

    @Test
    void anonymousRejectedOnListRoles() {
        assertThatThrownBy(() -> controller.listRoles(null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void nonAdminRejectedOnListRoles() {
        assertThatThrownBy(() -> controller.listRoles(user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }

    @Test
    void adminListRolesPassthrough() {
        when(service.listRoles()).thenReturn(List.of(Map.of("code", "admin")));
        assertThat(controller.listRoles(admin)).hasSize(1);
    }

    @Test
    void adminListPermissionsPassthrough() {
        when(service.listPermissions()).thenReturn(List.of(Map.of("code", "workbench.view")));
        assertThat(controller.listPermissions(admin)).hasSize(1);
    }

    @Test
    void nonAdminRejectedOnUpdatePermissions() {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("permissionIds");
        assertThatThrownBy(() -> controller.updatePermissions(UUID.randomUUID(), body, user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
        verify(service, never()).updateRolePermissions(any(), any(), any());
    }

    @Test
    void adminUpdatePermissionsPassthrough() {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode ids = body.putArray("permissionIds");
        ids.add("aaaaaaaa-0000-0000-0000-000000000001");
        when(service.updateRolePermissions(eq(admin.id()), any(), any()))
                .thenReturn(Map.of("ok", true, "before", List.of(), "after", List.of()));

        Map<String, Object> result = controller.updatePermissions(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), body, admin);

        assertThat(result).containsEntry("ok", true);
        verify(service).updateRolePermissions(eq(admin.id()),
                eq(UUID.fromString("00000000-0000-0000-0000-000000000001")), any());
    }
}
