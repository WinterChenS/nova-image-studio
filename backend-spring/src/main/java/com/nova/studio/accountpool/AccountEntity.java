package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nova.studio.infra.JsonbTypeHandler;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus entity for {@code ai_accounts} (global account
 * pool). {@code model_scope} / {@code health} stay String + service-layer
 * JSONB serialization (same minimal-change convention as
 * {@code capabilities} on the legacy {@code models} table).
 */
@TableName("ai_accounts")
public class AccountEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    @TableField("name")
    private String name;
    @TableField("protocol")
    private String protocol;
    @TableField("base_url")
    private String baseUrl;
    @TableField("api_key_enc")
    private String apiKeyEnc;
    @TableField(value = "model_scope", typeHandler = JsonbTypeHandler.class)
    private String modelScopeJson;
    @TableField("status")
    private String status;
    @TableField("priority")
    private Integer priority;
    @TableField("monthly_cap_cost")
    private BigDecimal monthlyCapCost;
    @TableField(value = "health", typeHandler = JsonbTypeHandler.class)
    private String healthJson;
    @TableField("remark")
    private String remark;
    @TableField("created_by")
    private UUID createdBy;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKeyEnc() { return apiKeyEnc; }
    public void setApiKeyEnc(String apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
    public String getModelScopeJson() { return modelScopeJson; }
    public void setModelScopeJson(String modelScopeJson) { this.modelScopeJson = modelScopeJson; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    public BigDecimal getMonthlyCapCost() { return monthlyCapCost; }
    public void setMonthlyCapCost(BigDecimal monthlyCapCost) { this.monthlyCapCost = monthlyCapCost; }
    public String getHealthJson() { return healthJson; }
    public void setHealthJson(String healthJson) { this.healthJson = healthJson; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
