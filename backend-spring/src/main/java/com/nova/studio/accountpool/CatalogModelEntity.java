package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nova.studio.infra.JsonbTypeHandler;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus entity for {@code ai_models} (global model
 * catalog, replaces the per-user {@code models} semantics; old table is
 * logically frozen until P2 cleanup).
 */
@TableName("ai_models")
public class CatalogModelEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    @TableField("type")
    private String type;
    @TableField("protocol")
    private String protocol;
    @TableField("name")
    private String name;
    @TableField("model_id")
    private String modelId;
    @TableField("base_url")
    private String baseUrl;
    @TableField(value = "capabilities", typeHandler = JsonbTypeHandler.class)
    private String capabilitiesJson;
    @TableField("builtin_preset_id")
    private String builtinPresetId;
    @TableField("enabled")
    private Boolean enabled;
    @TableField("created_by")
    private UUID createdBy;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getCapabilitiesJson() { return capabilitiesJson; }
    public void setCapabilitiesJson(String capabilitiesJson) { this.capabilitiesJson = capabilitiesJson; }
    public String getBuiltinPresetId() { return builtinPresetId; }
    public void setBuiltinPresetId(String builtinPresetId) { this.builtinPresetId = builtinPresetId; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
