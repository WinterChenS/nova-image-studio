package com.nova.studio.web;

import com.nova.studio.audit.AuditQueryService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T10 (WIN-28) — /api/nova/admin/usage authorization (A9/A1: admin only;
 * 普通用户 403) and query/export delegation.
 */
class AdminUsageControllerTest {

    private final AuditQueryService service = mock(AuditQueryService.class);
    private AdminUsageController controller;

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "boss", "admin");
    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new AdminUsageController(service);
    }

    @Test
    void anonymousRejected() {
        assertThatThrownBy(() -> controller.query(null, null, null, null, null, null, null, null, 1, 50, null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void nonAdminRejected() {
        assertThatThrownBy(() -> controller.query(null, null, null, null, null, null, null, null, 1, 50, user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
        verify(service, never()).query(any(), anyInt(), anyInt());
    }

    @Test
    void adminQueryPassthrough() {
        when(service.query(any(), anyInt(), anyInt())).thenReturn(Map.of("total", 0L, "items", java.util.List.of()));
        assertThat(controller.query(null, null, null, null, null, null, null, null, 1, 50, admin)).isNotNull();
    }

    @Test
    void nonAdminRejectedOnExport() {
        assertThatThrownBy(() -> controller.export(null, null, null, null, null, null, null, null, user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }

    @Test
    void adminExportPassthrough() {
        when(service.exportCsv(any())).thenReturn("时间,用户\n");
        assertThat(controller.export(null, null, null, null, null, null, null, null, admin)).isNotNull();
    }
}
