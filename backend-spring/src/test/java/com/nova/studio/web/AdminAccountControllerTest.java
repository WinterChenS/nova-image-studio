package com.nova.studio.web;

import com.nova.studio.accountpool.AccountService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
 * T3 (WIN-28) — /api/nova/admin/accounts authorization + CRUD/test/recover
 * delegation (A1/A4): 401 anonymous / 403 non-admin / admin passthrough.
 */
class AdminAccountControllerTest {

    private final AccountService service = mock(AccountService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AdminAccountController controller;

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "boss", "admin");
    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new AdminAccountController(service);
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
        when(service.list()).thenReturn(java.util.List.of(Map.of("id", "a1")));
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
    void adminTestConnectionPassthrough() {
        when(service.testConnection(any())).thenReturn(Map.of("ok", true));
        assertThat(controller.test(UUID.randomUUID(), admin).get("ok")).isEqualTo(true);
    }

    @Test
    void nonAdminRejectedOnTestConnection() {
        assertThatThrownBy(() -> controller.test(UUID.randomUUID(), user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }

    @Test
    void nonAdminRejectedOnRecover() {
        assertThatThrownBy(() -> controller.recover(UUID.randomUUID(), user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }

    @Test
    void adminRecoverPassthrough() {
        when(service.recover(any(), any())).thenReturn(Map.of("status", "active"));
        assertThat(controller.recover(UUID.randomUUID(), admin).get("status")).isEqualTo("active");
    }

    @Test
    void adminDeletePassthrough() {
        controller.delete(UUID.randomUUID(), admin);
        verify(service).delete(any(), any());
    }
}
