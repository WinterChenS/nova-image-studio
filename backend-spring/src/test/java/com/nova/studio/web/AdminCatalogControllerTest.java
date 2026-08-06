package com.nova.studio.web;

import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
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
 * T2 (WIN-28) — /api/nova/admin/models authorization (A1: 普通用户 403 无入口)
 * and CRUD delegation: 401 anonymous / 403 non-admin / admin passthrough.
 */
class AdminCatalogControllerTest {

    private final CatalogModelService service = mock(CatalogModelService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AdminCatalogController controller;

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "boss", "admin");
    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new AdminCatalogController(service);
    }

    @Test
    void anonymousRejectedOnList() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void nonAdminRejectedOnList() {
        assertThatThrownBy(() -> controller.list(user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }

    @Test
    void adminListPassthrough() {
        when(service.listAdmin()).thenReturn(List.of(Map.of("id", "m1")));
        assertThat(controller.list(admin)).hasSize(1);
    }

    @Test
    void nonAdminRejectedOnCreate() {
        ObjectNode body = mapper.createObjectNode();
        assertThatThrownBy(() -> controller.create(body, user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
        verify(service, never()).create(any(), any());
    }

    @Test
    void adminCreatePassthrough() {
        when(service.create(any(), any())).thenReturn(Map.of("id", "m1"));
        assertThat(controller.create(mapper.createObjectNode(), admin)).isNotNull();
    }

    @Test
    void adminDeletePassthrough() {
        controller.delete(UUID.randomUUID(), admin);
        verify(service).delete(eq(admin.id()), any());
    }

    @Test
    void nonAdminRejectedOnDelete() {
        assertThatThrownBy(() -> controller.delete(UUID.randomUUID(), user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }
}
