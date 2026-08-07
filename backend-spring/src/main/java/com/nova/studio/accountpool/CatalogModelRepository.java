package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * WIN-28 (V5) — {@code ai_models} catalog access (T2). All reads go through
 * the MyBatis-Plus BaseMapper; the update/delete keep explicit SQL.
 */
@Repository
public class CatalogModelRepository {

    /** Public row shape (service/test contract). */
    public record Row(UUID id, String type, String protocol, String name, String modelId,
                      String baseUrl, String capabilitiesJson, String builtinPresetId, Boolean enabled,
                      UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    private final CatalogModelMapper mapper;

    public CatalogModelRepository(CatalogModelMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<Row> findById(UUID id) {
        return Optional.ofNullable(mapper.selectById(id)).map(CatalogModelRepository::toRow);
    }

    public List<Row> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<CatalogModelEntity>()
                .orderByAsc(CatalogModelEntity::getType)
                .orderByAsc(CatalogModelEntity::getCreatedAt)).stream()
                .map(CatalogModelRepository::toRow).toList();
    }

    public List<Row> findAllEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<CatalogModelEntity>()
                .eq(CatalogModelEntity::getEnabled, true)
                .orderByAsc(CatalogModelEntity::getType)
                .orderByAsc(CatalogModelEntity::getCreatedAt)).stream()
                .map(CatalogModelRepository::toRow).toList();
    }

    public boolean existsProtocolModelId(String protocol, String modelId) {
        Long count = mapper.selectCount(new LambdaQueryWrapper<CatalogModelEntity>()
                .eq(CatalogModelEntity::getProtocol, protocol)
                .eq(CatalogModelEntity::getModelId, modelId));
        return count != null && count > 0;
    }

    public UUID insert(String type, String protocol, String name, String modelId, String baseUrl,
                       String capabilitiesJson, String builtinPresetId, Boolean enabled, UUID createdBy) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        CatalogModelEntity e = new CatalogModelEntity();
        e.setId(id);
        e.setType(type);
        e.setProtocol(protocol);
        e.setName(name);
        e.setModelId(modelId);
        e.setBaseUrl(baseUrl);
        e.setCapabilitiesJson(capabilitiesJson);
        e.setBuiltinPresetId(builtinPresetId);
        e.setEnabled(enabled == null || enabled);
        e.setCreatedBy(createdBy);
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        mapper.insert(e);
        return id;
    }

    public int update(UUID id, String type, String protocol, String name, String modelId, String baseUrl,
                      String capabilitiesJson, String builtinPresetId, Boolean enabled) {
        return mapper.updateRow(id, type, protocol, name, modelId, baseUrl, capabilitiesJson,
                builtinPresetId, enabled, Instant.now());
    }

    public int deleteById(UUID id) {
        return mapper.deleteById(id);
    }

    private static Row toRow(CatalogModelEntity e) {
        return new Row(e.getId(), e.getType(), e.getProtocol(), e.getName(), e.getModelId(),
                e.getBaseUrl(), e.getCapabilitiesJson(), e.getBuiltinPresetId(), e.getEnabled(),
                e.getCreatedBy(), e.getCreatedAt(), e.getUpdatedAt());
    }
}
