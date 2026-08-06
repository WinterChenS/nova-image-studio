package com.nova.studio.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * users table access (T2.2). WIN-16 (ADR-11): migrated from JdbcTemplate to
 * MyBatis-Plus ({@link UserMapper} + {@link UserEntity}); the public method
 * signatures and the {@link UserRow} record are unchanged so the service
 * layer and its tests stay untouched (strategy A, ARCH C.3.2.5).
 */
@Repository
public class UserRepository {

    /** Public row shape (service/test contract). */
    public record UserRow(UUID id, String username, String passwordHash, String role,
                          String status, Instant lastLoginAt,
                          Instant createdAt, Instant updatedAt) {
    }

    private final UserMapper mapper;

    public UserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<UserRow> findById(UUID id) {
        return Optional.ofNullable(mapper.selectById(id)).map(UserRepository::toRow);
    }

    public Optional<UserRow> findByUsername(String username) {
        UserEntity entity = mapper.selectOne(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        return Optional.ofNullable(entity).map(UserRepository::toRow);
    }

    public boolean existsByUsername(String username) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getUsername, username));
        return count != null && count > 0;
    }

    public UUID insert(String username, String passwordHash, String role) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        UserEntity entity = new UserEntity();
        entity.setId(id);
        entity.setUsername(username);
        entity.setPasswordHash(passwordHash);
        entity.setRole(role);
        entity.setStatus("active");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return id;
    }

    /** WIN-22 (T6): all users (admin console list), newest first. */
    public java.util.List<UserRow> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<UserEntity>()
                        .orderByDesc(UserEntity::getCreatedAt))
                .stream().map(UserRepository::toRow).toList();
    }

    /** WIN-22 (T6): count users with a given role — used by the last-admin guard. */
    public long countByRole(String role) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<UserEntity>()
                .eq(UserEntity::getRole, role));
        return count == null ? 0 : count;
    }

    /** WIN-22 (T6): update status / role / password hash / last login. */
    public void updateStatus(UUID id, String status) {
        UserEntity patch = new UserEntity();
        patch.setId(id);
        patch.setStatus(status);
        patch.setUpdatedAt(Instant.now());
        mapper.updateById(patch);
    }

    public void updateRole(UUID id, String role) {
        UserEntity patch = new UserEntity();
        patch.setId(id);
        patch.setRole(role);
        patch.setUpdatedAt(Instant.now());
        mapper.updateById(patch);
    }

    public void updatePasswordHash(UUID id, String passwordHash) {
        UserEntity patch = new UserEntity();
        patch.setId(id);
        patch.setPasswordHash(passwordHash);
        patch.setUpdatedAt(Instant.now());
        mapper.updateById(patch);
    }

    public void recordLogin(UUID id, Instant at) {
        UserEntity patch = new UserEntity();
        patch.setId(id);
        patch.setLastLoginAt(at);
        mapper.updateById(patch);
    }

    /** Test-only helper: hard-delete a user row (settings/models cascade via FK). */
    public void deleteByIdForTest(UUID id) {
        mapper.deleteById(id);
    }

    private static UserRow toRow(UserEntity e) {
        return new UserRow(e.getId(), e.getUsername(), e.getPasswordHash(), e.getRole(),
                e.getStatus(), e.getLastLoginAt(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
