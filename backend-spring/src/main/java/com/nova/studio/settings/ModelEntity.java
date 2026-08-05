package com.nova.studio.settings;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-16 (ADR-11) — MyBatis-Plus entity for {@code models} (mutable POJO so
 * {@code LambdaQueryWrapper} resolves {@code getXxx()} accessors; the public
 * row shape stays {@link ModelRepository.ModelRow}). The {@code capabilities}
 * JSONB column stays a String + service-layer serialization (minimal change).
 */
@TableName("models")
public class ModelEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    @TableField("user_id")
    private UUID userId;
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
    @TableField("api_key_enc")
    private String apiKeyEnc;
    @TableField(value = "capabilities", typeHandler = com.nova.studio.infra.JsonbTypeHandler.class)
    private String capabilitiesJson;
    @TableField("builtin_preset_id")
    private String builtinPresetId;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getProtocol() {
        return protocol;
    }

    public void setProtocol(String protocol) {
        this.protocol = protocol;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyEnc() {
        return apiKeyEnc;
    }

    public void setApiKeyEnc(String apiKeyEnc) {
        this.apiKeyEnc = apiKeyEnc;
    }

    public String getCapabilitiesJson() {
        return capabilitiesJson;
    }

    public void setCapabilitiesJson(String capabilitiesJson) {
        this.capabilitiesJson = capabilitiesJson;
    }

    public String getBuiltinPresetId() {
        return builtinPresetId;
    }

    public void setBuiltinPresetId(String builtinPresetId) {
        this.builtinPresetId = builtinPresetId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
