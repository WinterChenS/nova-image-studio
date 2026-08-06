package com.nova.studio.auth;

import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M2 T2.2 — register/login validation, bcrypt hashing, unique username and
 * credential verification (repository mocked, real bcrypt + JWT).
 */
class UserServiceTest {

    private UserRepository repository;
    private UserService service;

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        repository = mock(UserRepository.class);
        service = new UserService(repository, new JwtService("S3GYs4Qf3sLwAgajjSi4/ADjR00ldw9RX2iwL5NgRr6fGU98/24hTLFHu0JySZG7"));
    }

    @Test
    void registersWithBcryptHash() {
        when(repository.existsByUsername("alice")).thenReturn(false);
        when(repository.insert(anyString(), anyString(), anyString())).thenReturn(USER_ID);
        var user = service.register("alice", "secret123");
        assertThat(user).containsEntry("id", USER_ID.toString()).containsEntry("username", "alice");
        verify(repository).insert(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsShortPassword() {
        assertThatThrownBy(() -> service.register("alice", "12345"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("密码长度至少 6 位");
    }

    @Test
    void rejectsInvalidUsername() {
        assertThatThrownBy(() -> service.register("a", "secret123"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.register("bad name!", "secret123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateUsername() {
        when(repository.existsByUsername("alice")).thenReturn(true);
        assertThatThrownBy(() -> service.register("alice", "secret123"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(409);
                    assertThat(e.getCode()).isEqualTo("USERNAME_TAKEN");
                });
        verify(repository, never()).insert(anyString(), anyString(), anyString());
    }

    @Test
    void loginSucceedsAndReturnsToken() {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("secret123");
        when(repository.findByUsername("alice")).thenReturn(Optional.of(
                new UserRepository.UserRow(USER_ID, "alice", hash, "user", "active", null, null, null)));
        var body = service.login("alice", "secret123");
        assertThat(body).containsKey("token");
        assertThat(body.get("user")).isInstanceOf(java.util.Map.class);
    }

    @Test
    void loginRejectsWrongPassword() {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("secret123");
        when(repository.findByUsername("alice")).thenReturn(Optional.of(
                new UserRepository.UserRow(USER_ID, "alice", hash, "user", "active", null, null, null)));
        assertThatThrownBy(() -> service.login("alice", "wrong-pass"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(401);
                    assertThat(e.getCode()).isEqualTo("INVALID_CREDENTIALS");
                });
    }

    @Test
    void loginRejectsUnknownUser() {
        when(repository.findByUsername("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.login("ghost", "secret123"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void loginRejectsDisabledAccount() {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("secret123");
        when(repository.findByUsername("alice")).thenReturn(Optional.of(
                new UserRepository.UserRow(USER_ID, "alice", hash, "user", "disabled", null, null, null)));
        assertThatThrownBy(() -> service.login("alice", "secret123"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(401);
                    assertThat(e.getCode()).isEqualTo("USER_DISABLED");
                });
    }

    @Test
    void loginRecordsLastLogin() {
        String hash = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode("secret123");
        when(repository.findByUsername("alice")).thenReturn(Optional.of(
                new UserRepository.UserRow(USER_ID, "alice", hash, "user", "active", null, null, null)));
        service.login("alice", "secret123");
        verify(repository).recordLogin(eq(USER_ID), any());
    }
}
