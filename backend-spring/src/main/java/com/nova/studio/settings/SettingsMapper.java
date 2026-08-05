package com.nova.studio.settings;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-16 (ADR-11) — MyBatis-Plus mapper for the {@code settings} table. The
 * entity {@link SettingsEntity} has a composite primary key ({@code user_id +
 * key}) which MyBatis-Plus does not model — reads go through {@link BaseMapper}
 * wrappers and the upsert keeps the original {@code INSERT ... ON CONFLICT}
 * SQL verbatim (ARCH C.3.2.5).
 */
@Mapper
public interface SettingsMapper extends BaseMapper<SettingsEntity> {

    @Insert("""
            INSERT INTO settings (user_id, key, value, value_type, description, updated_at)
            VALUES (#{userId}, #{key}, #{value}::jsonb, #{valueType}, NULL, #{updatedAt})
            ON CONFLICT (user_id, key) DO UPDATE SET
                value = EXCLUDED.value,
                value_type = EXCLUDED.value_type,
                updated_at = EXCLUDED.updated_at
            """)
    void upsert(@Param("userId") UUID userId, @Param("key") String key,
                @Param("value") String valueJson, @Param("valueType") String valueType,
                @Param("updatedAt") Instant updatedAt);
}
