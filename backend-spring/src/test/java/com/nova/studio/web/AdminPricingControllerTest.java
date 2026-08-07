package com.nova.studio.web;

import com.nova.studio.accountpool.PricingService;
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
 * T9 (WIN-28) — /api/nova/admin/pricing authorization (A1: 普通用户 403) and
 * upsert/list/delete delegation.
 */
class AdminPricingControllerTest {

    private final PricingService service = mock(PricingService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private AdminPricingController controller;

    private final AuthUser admin = new AuthUser(UUID.randomUUID(), "boss", "admin");
    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new AdminPricingController(service);
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
    void adminUpsertPassthrough() {
        when(service.upsert(any(), any())).thenReturn(Map.of("modelId", "m1"));
        ObjectNode body = mapper.createObjectNode();
        assertThat(controller.upsert(body, admin)).isNotNull();
    }

    @Test
    void nonAdminRejectedOnUpsert() {
        assertThatThrownBy(() -> controller.upsert(mapper.createObjectNode(), user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
        verify(service, never()).upsert(any(), any());
    }

    @Test
    void nonAdminRejectedOnDelete() {
        assertThatThrownBy(() -> controller.delete(UUID.randomUUID(), null, user))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(403));
    }
}
