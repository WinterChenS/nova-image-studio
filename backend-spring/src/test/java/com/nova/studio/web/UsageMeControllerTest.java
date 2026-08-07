package com.nova.studio.web;

import com.nova.studio.audit.UsageMeService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T24 (WIN-29) — {@code GET /api/nova/usage/me}: 401 anonymous, and the query
 * is always bound to the authenticated user's own id (A11 用户间隔离). Method
 * security ({@code PERM_usage.me}) is enforced by the filter chain.
 */
class UsageMeControllerTest {

    private final UsageMeService service = mock(UsageMeService.class);
    private UsageMeController controller;

    private final AuthUser me = new AuthUser(UUID.fromString("11111111-1111-1111-1111-111111111111"), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new UsageMeController(service);
    }

    @Test
    void anonymousRejected() {
        assertThatThrownBy(() -> controller.myUsage(null, null, 1, 20, null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void queryIsScopedToAuthenticatedUser() {
        when(service.myUsage(me.id(), null, null, 1, 20)).thenReturn(Map.of("total", 0L));

        Map<String, Object> result = controller.myUsage(null, null, 1, 20, me);

        assertThat(result).containsEntry("total", 0L);
        verify(service).myUsage(me.id(), null, null, 1, 20);
    }
}
