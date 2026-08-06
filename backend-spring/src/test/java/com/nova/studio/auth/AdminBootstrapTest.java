package com.nova.studio.auth;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

import java.time.Instant;
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
 */
class AdminBootstrapTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final UserMapper userMapper = mock(UserMapper.class);
    private final ApplicationArguments args = mock(ApplicationArguments.class);

    @BeforeEach
    void setUp() {
        when(userMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void noOpWhenAdminUsernameUnset() {
        new AdminBootstrap(userRepository, userMapper, "").run(args);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void skipsWhenUserNotRegistered() {
        when(userRepository.existsByUsername("boss")).thenReturn(false);
        new AdminBootstrap(userRepository, userMapper, "boss").run(args);
        verify(userMapper, never()).update(any(), any());
    }

    @Test
    void promotesRegisteredUser() {
        when(userRepository.existsByUsername("boss")).thenReturn(true);
        new AdminBootstrap(userRepository, userMapper, "boss").run(args);
        verify(userMapper).update(eq(null), any(UpdateWrapper.class));
    }

    @Test
    void trimsConfiguredUsername() {
        when(userRepository.existsByUsername("boss")).thenReturn(true);
        new AdminBootstrap(userRepository, userMapper, "  boss  ").run(args);
        verify(userRepository).existsByUsername("boss");
    }

    @Test
    void swallowDbErrors() {
        when(userRepository.existsByUsername("boss")).thenThrow(new RuntimeException("db down"));
        // must not propagate — startup keeps going
        new AdminBootstrap(userRepository, userMapper, "boss").run(args);
    }
}
