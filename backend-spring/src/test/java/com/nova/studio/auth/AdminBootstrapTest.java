package com.nova.studio.auth;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.nova.studio.rbac.UserPermissionService;
import com.nova.studio.rbac.UserRoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T3.1 — {@link AdminBootstrap}: env-driven admin promotion is a no-op when
 * {@code NOVA_ADMIN_USERNAME} is unset, skips when the user is not registered,
 * and promotes (idempotent role update) when the user exists.
 * WIN-25 (T15) — 提权双写 user_roles + 失效权限缓存。
 */
class AdminBootstrapTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
    private final UserPermissionService permissionService = mock(UserPermissionService.class);
    private final ApplicationArguments args = mock(ApplicationArguments.class);
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        when(userMapper.update(any(), any())).thenReturn(1);
    }

    private AdminBootstrap bootstrap(String username) {
        return new AdminBootstrap(userRepository, userMapper, userRoleRepository, permissionService, username);
    }

    @Test
    void noOpWhenAdminUsernameUnset() {
        bootstrap("").run(args);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void skipsWhenUserNotRegistered() {
        when(userRepository.existsByUsername("boss")).thenReturn(false);
        bootstrap("boss").run(args);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void promotesRegisteredUser() {
        when(userRepository.existsByUsername("boss")).thenReturn(true);
        when(userRepository.findByUsername("boss")).thenReturn(Optional.of(
                new UserRepository.UserRow(USER_ID, "boss", "hash", "user", "active", null, null, null)));
        bootstrap("boss").run(args);
        verify(userMapper).update(eq(null), any(UpdateWrapper.class));
        // T15 (R4): 双写 user_roles + 缓存失效
        verify(userRoleRepository).assignRole(USER_ID, "admin");
        verify(permissionService).invalidate(USER_ID);
    }

    @Test
    void trimsConfiguredUsername() {
        when(userRepository.existsByUsername("boss")).thenReturn(true);
        when(userRepository.findByUsername("boss")).thenReturn(Optional.of(
                new UserRepository.UserRow(USER_ID, "boss", "hash", "user", "active", null, null, null)));
        bootstrap("  boss  ").run(args);
        verify(userRepository).existsByUsername("boss");
    }

    @Test
    void swallowDbErrors() {
        when(userRepository.existsByUsername("boss")).thenThrow(new RuntimeException("db down"));
        // must not propagate — startup keeps going
        bootstrap("boss").run(args);
    }
}
