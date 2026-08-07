package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus mapper for {@code ai_models}. Reads use
 * BaseMapper wrappers; the update keeps explicit SQL so every column is
 * always written (mirrors the {@code models} ModelMapper convention).
 */
@Mapper
public interface CatalogModelMapper extends BaseMapper<CatalogModelEntity> {

    @Update("""
            UPDATE ai_models SET
                type = #{type}, protocol = #{protocol}, name = #{name}, model_id = #{modelId},
                base_url = #{baseUrl}, capabilities = #{capabilitiesJson}::jsonb,
                builtin_preset_id = #{builtinPresetId}, enabled = #{enabled},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int updateRow(@Param("id") UUID id,
                  @Param("type") String type, @Param("protocol") String protocol,
                  @Param("name") String name, @Param("modelId") String modelId,
                  @Param("baseUrl") String baseUrl, @Param("capabilitiesJson") String capabilitiesJson,
                  @Param("builtinPresetId") String builtinPresetId, @Param("enabled") Boolean enabled,
                  @Param("updatedAt") Instant updatedAt);
}
