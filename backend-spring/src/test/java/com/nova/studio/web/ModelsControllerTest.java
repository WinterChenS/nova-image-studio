package com.nova.studio.web;

import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T2 (WIN-28, A2) — {@code GET /api/nova/models} serves the read-only global
 * catalog (login required); the old per-user CRUD endpoints are gone (POST/
 * PUT/DELETE → 404 via Spring's missing mapping, A2 用户级 CRUD 下线).
 */
class ModelsControllerTest {

    private final CatalogModelService catalogModelService = mock(CatalogModelService.class);
    private ModelsController controller;

    private final AuthUser user = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new ModelsController(catalogModelService);
    }

    @Test
    void anonymousRejected() {
        assertThatThrownBy(() -> controller.list(null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void returnsPublicCatalogForLoggedInUser() {
        when(catalogModelService.listPublicCatalog()).thenReturn(List.of(
                Map.of("id", "m1", "enabled", true, "available", true)));
        assertThat(controller.list(user)).hasSize(1);
        assertThat(controller.list(user).getFirst().get("available")).isEqualTo(true);
    }
}
