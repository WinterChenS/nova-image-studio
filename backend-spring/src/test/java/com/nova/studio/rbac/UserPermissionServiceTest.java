package com.nova.studio.rbac;

import com.nova.studio.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T14 (WIN-30, ADR-29/R4) — {@link UserPermissionService}: permissions come
 * from user_roles → role_permissions (single source of truth), empty
 * user_roles falls back to users.role (V6 backfill safety), Caffeine cache
 * serves repeated reads and explicit invalidation reloads from DB.
 */
class UserPermissionServiceTest {

    private static final UUID USER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ADMIN_ROLE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ROLE = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private UserRoleRepository roleRepository;
    private UserRepository userRepository;
    private UserPermissionService service;

    @BeforeEach
    void setUp() {
        roleRepository = mock(UserRoleRepository.class);
        userRepository = mock(UserRepository.class);
        service = new UserPermissionService(roleRepository, userRepository);
    }

    private UserRepository.UserRow userRow(String role) {
        return new UserRepository.UserRow(USER, "alice", "hash", role, "active",
                Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    void permissionsComeFromUserRolesWhenPresent() {
        when(roleRepository.roleCodesForUser(USER)).thenReturn(List.of("user"));
        when(roleRepository.roleIdByCode("user")).thenReturn(Optional.of(USER_ROLE));
        when(roleRepository.permissionCodesForRoles(List.of(USER_ROLE)))
                .thenReturn(List.of("workbench.view", "usage.me"));

        UserPermissionService.UserPermissions loaded = service.load(USER);

        assertThat(loaded.roles()).containsExactly("user");
        assertThat(loaded.permissions()).containsExactly("usage.me", "workbench.view");
    }

    @Test
    void fallsBackToUsersRoleWhenUserRolesEmpty() {
        when(roleRepository.roleCodesForUser(USER)).thenReturn(List.of());
        when(userRepository.findById(USER)).thenReturn(Optional.of(userRow("admin")));
        when(roleRepository.roleIdByCode("admin")).thenReturn(Optional.of(ADMIN_ROLE));
        when(roleRepository.permissionCodesForRoles(List.of(ADMIN_ROLE)))
                .thenReturn(List.of("account.manage", "audit.view"));

        UserPermissionService.UserPermissions loaded = service.load(USER);

        assertThat(loaded.roles()).containsExactly("admin");
        assertThat(loaded.permissions()).contains("account.manage", "audit.view");
    }

    @Test
    void cacheServesSecondReadWithoutDbHit() {
        when(roleRepository.roleCodesForUser(USER)).thenReturn(List.of("user"));
        when(roleRepository.roleIdByCode("user")).thenReturn(Optional.of(USER_ROLE));
        when(roleRepository.permissionCodesForRoles(any())).thenReturn(List.of("workbench.view"));

        service.load(USER);
        service.load(USER);

        // 两次 load 只应触发一次 roleCodesForUser（缓存命中）
        org.mockito.Mockito.verify(roleRepository, org.mockito.Mockito.times(1)).roleCodesForUser(USER);
    }

    @Test
    void invalidateForcesReload() {
        when(roleRepository.roleCodesForUser(USER)).thenReturn(List.of("user"));
        when(roleRepository.roleIdByCode("user")).thenReturn(Optional.of(USER_ROLE));
        when(roleRepository.permissionCodesForRoles(any())).thenReturn(List.of("workbench.view"));

        service.load(USER);
        service.invalidate(USER);
        service.load(USER);

        org.mockito.Mockito.verify(roleRepository, org.mockito.Mockito.times(2)).roleCodesForUser(USER);
    }

    @Test
    void adminRoleCarriesAllSeededPermissions() {
        when(roleRepository.roleCodesForUser(USER)).thenReturn(List.of("admin"));
        when(roleRepository.roleIdByCode("admin")).thenReturn(Optional.of(ADMIN_ROLE));
        when(roleRepository.permissionCodesForRoles(List.of(ADMIN_ROLE)))
                .thenReturn(List.of("workbench.view", "usage.me", "admin.console.view", "project.manage",
                        "asset.manage", "user.manage", "account.manage", "account.test",
                        "model.catalog.manage", "pricing.manage", "audit.view", "audit.export", "rbac.manage"));

        UserPermissionService.UserPermissions loaded = service.load(USER);

        assertThat(loaded.permissions()).hasSize(13);
        assertThat(loaded.permissions()).contains("account.manage", "audit.export");
    }
}
