package com.nova.studio.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * users table access (T2.2). Row mapper is shared with {@link UserService}.
 */
@Repository
public class UserRepository {

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRow(UUID id, String username, String passwordHash, String role, Instant createdAt, Instant updatedAt) {
    }

    public Optional<UserRow> findById(UUID id) {
        List<UserRow> rows = jdbc.query(
                "SELECT id, username, password_hash, role, created_at, updated_at FROM users WHERE id = ?",
                (rs, i) -> new UserRow(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                id);
        return rows.stream().findFirst();
    }

    public Optional<UserRow> findByUsername(String username) {
        List<UserRow> rows = jdbc.query(
                "SELECT id, username, password_hash, role, created_at, updated_at FROM users WHERE username = ?",
                (rs, i) -> new UserRow(
                        rs.getObject("id", java.util.UUID.class),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("role"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("updated_at").toInstant()),
                username);
        return rows.stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username);
        return count != null && count > 0;
    }

    public UUID insert(String username, String passwordHash, String role) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO users (id, username, password_hash, role, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, username, passwordHash, role, Timestamp.from(now), Timestamp.from(now));
        return id;
    }
}
