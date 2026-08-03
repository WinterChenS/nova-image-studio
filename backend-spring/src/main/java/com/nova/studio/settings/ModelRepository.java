package com.nova.studio.settings;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * models table access (T2.1). All queries are scoped by user_id (isolation).
 * WIN-16 (ADR-11): migrated from JdbcTemplate to MyBatis-Plus
 * ({@link ModelMapper} + {@link ModelEntity}); public signatures and the
 * {@link ModelRow} record are unchanged (strategy A, ARCH C.3.2.5). The
 * {@code capabilities} JSONB column stays a String + service-layer
 * serialization (minimal change).
 */
@Repository
public class ModelRepository {

    /** Public row shape (service/test contract, unchanged). */
    public record ModelRow(UUID id, UUID userId, String type, String protocol, String name,
                           String modelId, String baseUrl, String apiKeyEnc,
                           String capabilitiesJson, String builtinPresetId,
                           Instant createdAt, Instant updatedAt) {
    }

    private final ModelMapper mapper;

    public ModelRepository(ModelMapper mapper) {
        this.mapper = mapper;
    }

    public List<ModelRow> findByUserId(UUID userId) {
        return mapper.selectList(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getUserId, userId)
                .orderByAsc(ModelEntity::getCreatedAt)
                .orderByAsc(ModelEntity::getName)).stream()
                .map(ModelRepository::toRow)
                .toList();
    }

    public Optional<ModelRow> findByIdAndUser(UUID id, UUID userId) {
        ModelEntity entity = mapper.selectOne(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getId, id)
                .eq(ModelEntity::getUserId, userId));
        return Optional.ofNullable(entity).map(ModelRepository::toRow);
    }

    public boolean existsName(UUID userId, String type, String name, UUID excludeId) {
        LambdaQueryWrapper<ModelEntity> wrapper = new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getUserId, userId)
                .eq(ModelEntity::getType, type)
                .eq(ModelEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(ModelEntity::getId, excludeId);
        }
        Long count = mapper.selectCount(wrapper);
        return count != null && count > 0;
    }

    public UUID insert(UUID userId, String type, String protocol, String name, String modelId,
                       String baseUrl, String apiKeyEnc, String capabilitiesJson, String builtinPresetId) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        ModelEntity entity = new ModelEntity();
        entity.setId(id);
        entity.setUserId(userId);
        entity.setType(type);
        entity.setProtocol(protocol);
        entity.setName(name);
        entity.setModelId(modelId);
        entity.setBaseUrl(baseUrl);
        entity.setApiKeyEnc(apiKeyEnc);
        entity.setCapabilitiesJson(capabilitiesJson);
        entity.setBuiltinPresetId(builtinPresetId);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        mapper.insert(entity);
        return id;
    }

    public void update(UUID id, UUID userId, String type, String protocol, String name, String modelId,
                       String baseUrl, String apiKeyEnc, String capabilitiesJson, String builtinPresetId) {
        mapper.updateRow(id, userId, type, protocol, name, modelId, baseUrl, apiKeyEnc,
                capabilitiesJson, builtinPresetId, Instant.now());
    }

    public boolean delete(UUID id, UUID userId) {
        return mapper.delete(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getId, id)
                .eq(ModelEntity::getUserId, userId)) > 0;
    }

    public int countByUser(UUID userId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<ModelEntity>()
                .eq(ModelEntity::getUserId, userId));
        return count == null ? 0 : count.intValue();
    }

    private static ModelRow toRow(ModelEntity e) {
        return new ModelRow(e.getId(), e.getUserId(), e.getType(), e.getProtocol(), e.getName(),
                e.getModelId(), e.getBaseUrl(), e.getApiKeyEnc(), e.getCapabilitiesJson(),
                e.getBuiltinPresetId(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
