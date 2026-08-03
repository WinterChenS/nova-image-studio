package com.nova.studio.settings;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * models table access (T2.1). All queries are scoped by user_id (isolation).
 */
@Repository
public class ModelRepository {

    private final JdbcTemplate jdbc;

    public ModelRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Raw row — {@code apiKeyEnc} is the AES-GCM ciphertext (nullable). */
    public record ModelRow(UUID id, UUID userId, String type, String protocol, String name,
                           String modelId, String baseUrl, String apiKeyEnc,
                           String capabilitiesJson, String builtinPresetId,
                           Instant createdAt, Instant updatedAt) {
    }

    private ModelRow map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new ModelRow(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("type"),
                rs.getString("protocol"),
                rs.getString("name"),
                rs.getString("model_id"),
                rs.getString("base_url"),
                rs.getString("api_key_enc"),
                rs.getString("capabilities"),
                rs.getString("builtin_preset_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static final String SELECT_COLUMNS =
            "SELECT id, user_id, type, protocol, name, model_id, base_url, api_key_enc, capabilities, builtin_preset_id, created_at, updated_at FROM models";

    public List<ModelRow> findByUserId(UUID userId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE user_id = ? ORDER BY created_at, name", (this::map), userId);
    }

    public Optional<ModelRow> findByIdAndUser(UUID id, UUID userId) {
        List<ModelRow> rows = jdbc.query(SELECT_COLUMNS + " WHERE id = ? AND user_id = ?", (this::map), id, userId);
        return rows.stream().findFirst();
    }

    public boolean existsName(UUID userId, String type, String name, UUID excludeId) {
        Integer count;
        if (excludeId == null) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM models WHERE user_id = ? AND type = ? AND name = ?",
                    Integer.class, userId, type, name);
        } else {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM models WHERE user_id = ? AND type = ? AND name = ? AND id <> ?",
                    Integer.class, userId, type, name, excludeId);
        }
        return count != null && count > 0;
    }

    public UUID insert(UUID userId, String type, String protocol, String name, String modelId,
                       String baseUrl, String apiKeyEnc, String capabilitiesJson, String builtinPresetId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        jdbc.update(
                "INSERT INTO models (id, user_id, type, protocol, name, model_id, base_url, api_key_enc, capabilities, builtin_preset_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)",
                id, userId, type, protocol, name, modelId, baseUrl, apiKeyEnc, capabilitiesJson, builtinPresetId,
                Timestamp.from(now), Timestamp.from(now));
        return id;
    }

    public void update(UUID id, UUID userId, String type, String protocol, String name, String modelId,
                       String baseUrl, String apiKeyEnc, String capabilitiesJson, String builtinPresetId) {
        jdbc.update(
                "UPDATE models SET type = ?, protocol = ?, name = ?, model_id = ?, base_url = ?, api_key_enc = ?,"
                        + " capabilities = ?::jsonb, builtin_preset_id = ?, updated_at = ? WHERE id = ? AND user_id = ?",
                type, protocol, name, modelId, baseUrl, apiKeyEnc, capabilitiesJson, builtinPresetId,
                Timestamp.from(Instant.now()), id, userId);
    }

    public boolean delete(UUID id, UUID userId) {
        return jdbc.update("DELETE FROM models WHERE id = ? AND user_id = ?", id, userId) > 0;
    }

    public int countByUser(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM models WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }
}
