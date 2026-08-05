package com.nova.studio.settings;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-16 (ADR-11) — MyBatis-Plus mapper for the {@code models} table. Reads
 * and counts go through {@link BaseMapper} wrappers (all scoped by user_id);
 * the update keeps explicit SQL so every column is always written — including
 * a {@code NULL} {@code api_key_enc} (key not retyped on save) — preserving
 * the JdbcTemplate semantics exactly (ARCH C.3.2.5).
 */
@Mapper
public interface ModelMapper extends BaseMapper<ModelEntity> {

    @Update("""
            UPDATE models SET
                type = #{type}, protocol = #{protocol}, name = #{name}, model_id = #{modelId},
                base_url = #{baseUrl}, api_key_enc = #{apiKeyEnc},
                capabilities = #{capabilitiesJson}::jsonb, builtin_preset_id = #{builtinPresetId},
                updated_at = #{updatedAt}
            WHERE id = #{id} AND user_id = #{userId}
            """)
    int updateRow(@Param("id") UUID id, @Param("userId") UUID userId,
                  @Param("type") String type, @Param("protocol") String protocol,
                  @Param("name") String name, @Param("modelId") String modelId,
                  @Param("baseUrl") String baseUrl, @Param("apiKeyEnc") String apiKeyEnc,
                  @Param("capabilitiesJson") String capabilitiesJson,
                  @Param("builtinPresetId") String builtinPresetId,
                  @Param("updatedAt") Instant updatedAt);
}
