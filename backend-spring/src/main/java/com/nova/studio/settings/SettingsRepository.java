package com.nova.studio.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * settings table access (T2.1) — per-user JSONB key/value rows.
 */
@Repository
public class SettingsRepository {

    private final JdbcTemplate jdbc;

    public SettingsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** All settings for a user: key → raw JSON value string. */
    public Map<String, String> findAllByUser(UUID userId) {
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT key, value FROM settings WHERE user_id = ?",
                (rs, i) -> {
                    result.put(rs.getString("key"), rs.getString("value"));
                    return null;
                },
                userId);
        return result;
    }

    public void upsert(UUID userId, String key, String valueJson, String valueType) {
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO settings (user_id, key, value, value_type, description, updated_at)"
                        + " VALUES (?, ?, ?::jsonb, ?, NULL, ?)"
                        + " ON CONFLICT (user_id, key) DO UPDATE SET value = EXCLUDED.value,"
                        + " value_type = EXCLUDED.value_type, updated_at = EXCLUDED.updated_at",
                userId, key, valueJson, valueType, Timestamp.from(now));
    }

    public void delete(UUID userId, String key) {
        jdbc.update("DELETE FROM settings WHERE user_id = ? AND key = ?", userId, key);
    }
}
