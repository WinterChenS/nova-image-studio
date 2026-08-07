package com.nova.studio.auth;

import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.rbac.UserPermissionService;
import com.nova.studio.rbac.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
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
 * WIN-22 (F-40..F-42 / D.4 / R-8) — admin user management: self-operation and
 * last-admin guards, status/role updates and admin-set passwords (Q4).
 */
class AdminUserServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID MEMBER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private UserRepository repository;
    private UserRoleRepository userRoleRepository;
    private UserPermissionService permissionService;
    private AdminUserService service;

    private UserRepository.UserRow userRow(UUID id, String role, String status) {
        return new UserRepository.UserRow(id, id.toString().substring(0, 8), "hash", role, status, null,
                Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-08-01T00:00:00Z"));
    }

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        userRoleRepository = mock(UserRoleRepository.class);
        permissionService = mock(UserPermissionService.class);
        service = new AdminUserService(repository, userRoleRepository, permissionService);
    }

    @Test
    void cannotDisableSelf() {
        when(repository.findById(ADMIN_ID)).thenReturn(Optional.of(userRow(ADMIN_ID, "admin", "active")));
        assertThatThrownBy(() -> service.update(ADMIN_ID, ADMIN_ID, "disabled", null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("SELF_OPERATION");
                });
        verify(repository, never()).updateStatus(any(), any());
    }

    @Test
    void cannotDemoteSelf() {
        when(repository.findById(ADMIN_ID)).thenReturn(Optional.of(userRow(ADMIN_ID, "admin", "active")));
        assertThatThrownBy(() -> service.update(ADMIN_ID, ADMIN_ID, null, "user"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("SELF_OPERATION");
                });
    }

    @Test
    void cannotDisableLastAdmin() {
        when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(userRow(MEMBER_ID, "admin", "active")));
        when(repository.countByRole("admin")).thenReturn(1L);
        assertThatThrownBy(() -> service.update(ADMIN_ID, MEMBER_ID, "disabled", null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getCode()).isEqualTo("LAST_ADMIN");
                });
    }

    @Test
    void canDisableWhenAnotherAdminExists() {
        when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(userRow(MEMBER_ID, "user", "active")));
        when(repository.countByRole("admin")).thenReturn(2L);
        service.update(ADMIN_ID, MEMBER_ID, "disabled", null);
        verify(repository).updateStatus(MEMBER_ID, "disabled");
    }

    @Test
    void roleChangeDualWritesUserRolesAndInvalidatesCache() {
        when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(userRow(MEMBER_ID, "user", "active")));
        when(repository.countByRole("admin")).thenReturn(2L);
        service.update(ADMIN_ID, MEMBER_ID, null, "admin");
        verify(repository).updateRole(MEMBER_ID, "admin");
        verify(userRoleRepository).assignRole(MEMBER_ID, "admin");   // T15 (R4) 双写
        verify(permissionService).invalidate(MEMBER_ID);              // A14 缓存失效
    }

    @Test
    void resetPasswordHashesBcrypt() {
        when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(userRow(MEMBER_ID, "user", "active")));
        service.resetPassword(MEMBER_ID, "new-pass-123");
        verify(repository).updatePasswordHash(eq(MEMBER_ID), any());
    }

    @Test
    void resetPasswordRejectsShortPassword() {
        when(repository.findById(MEMBER_ID)).thenReturn(Optional.of(userRow(MEMBER_ID, "user", "active")));
        assertThatThrownBy(() -> service.resetPassword(MEMBER_ID, "12345"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).updatePasswordHash(any(), any());
    }

    @Test
    void updateUnknownUserReturns404() {
        when(repository.findById(MEMBER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(ADMIN_ID, MEMBER_ID, "disabled", null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(404);
                    assertThat(e.getCode()).isEqualTo("NOT_FOUND");
                });
    }
}
