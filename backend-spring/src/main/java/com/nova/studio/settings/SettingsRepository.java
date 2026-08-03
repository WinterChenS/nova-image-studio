package com.nova.studio.settings;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * settings table access (T2.1) — per-user JSONB key/value rows. WIN-16
 * (ADR-11): migrated from JdbcTemplate to MyBatis-Plus ({@link SettingsMapper}
 * + {@link SettingsEntity}); public signatures unchanged (strategy A, ARCH
 * C.3.2.5). The composite primary key ({@code user_id + key}) is not modeled
 * by MyBatis-Plus, so the upsert keeps the original {@code INSERT ... ON
 * CONFLICT} SQL.
 */
@Repository
public class SettingsRepository {

    private final SettingsMapper mapper;

    public SettingsRepository(SettingsMapper mapper) {
        this.mapper = mapper;
    }

    /** All settings for a user: key → raw JSON value string. */
    public Map<String, String> findAllByUser(UUID userId) {
        List<SettingsEntity> rows = mapper.selectList(new LambdaQueryWrapper<SettingsEntity>()
                .eq(SettingsEntity::getUserId, userId));
        Map<String, String> result = new LinkedHashMap<>();
        for (SettingsEntity row : rows) {
            result.put(row.getKey(), row.getValue());
        }
        return result;
    }

    public void upsert(UUID userId, String key, String valueJson, String valueType) {
        mapper.upsert(userId, key, valueJson, valueType, Instant.now());
    }

    public void delete(UUID userId, String key) {
        mapper.delete(new LambdaQueryWrapper<SettingsEntity>()
                .eq(SettingsEntity::getUserId, userId)
                .eq(SettingsEntity::getKey, key));
    }
}
