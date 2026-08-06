package com.nova.studio.accountpool;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus entity for {@code ai_model_pricing} (dual-rate
 * price table: per_request_price + price_per_token; UNIQUE(model_id,
 * currency)).
 */
@TableName("ai_model_pricing")
public class PricingEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    @TableField("model_id")
    private UUID modelId;
    @TableField("currency")
    private String currency;
    @TableField("per_request_price")
    private BigDecimal perRequestPrice;
    @TableField("price_per_token")
    private BigDecimal pricePerToken;
    @TableField("effective_from")
    private Instant effectiveFrom;
    @TableField("created_by")
    private UUID createdBy;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public BigDecimal getPerRequestPrice() { return perRequestPrice; }
    public void setPerRequestPrice(BigDecimal perRequestPrice) { this.perRequestPrice = perRequestPrice; }
    public BigDecimal getPricePerToken() { return pricePerToken; }
    public void setPricePerToken(BigDecimal pricePerToken) { this.pricePerToken = pricePerToken; }
    public Instant getEffectiveFrom() { return effectiveFrom; }
    public void setEffectiveFrom(Instant effectiveFrom) { this.effectiveFrom = effectiveFrom; }
    public UUID getCreatedBy() { return createdBy; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
