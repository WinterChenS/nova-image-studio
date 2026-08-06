package com.nova.studio.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * WIN-28 (V5) — MyBatis-Plus entity for {@code usage_records} (request-level
 * usage/cost detail; UNIQUE(ref_type, ref_id) idempotency R2).
 */
@TableName("usage_records")
public class UsageRecordEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private UUID userId;
    @TableField("account_id")
    private UUID accountId;
    @TableField("model_id")
    private UUID modelId;
    @TableField("protocol")
    private String protocol;
    @TableField("req_type")
    private String reqType;
    @TableField("ref_type")
    private String refType;
    @TableField("ref_id")
    private String refId;
    @TableField("status")
    private String status;
    @TableField("input_tokens")
    private Long inputTokens;
    @TableField("output_tokens")
    private Long outputTokens;
    @TableField("images")
    private Integer images;
    @TableField("cost")
    private BigDecimal cost;
    @TableField("currency")
    private String currency;
    @TableField("duration_ms")
    private Long durationMs;
    @TableField("created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getReqType() { return reqType; }
    public void setReqType(String reqType) { this.reqType = reqType; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getInputTokens() { return inputTokens; }
    public void setInputTokens(Long inputTokens) { this.inputTokens = inputTokens; }
    public Long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Long outputTokens) { this.outputTokens = outputTokens; }
    public Integer getImages() { return images; }
    public void setImages(Integer images) { this.images = images; }
    public BigDecimal getCost() { return cost; }
    public void setCost(BigDecimal cost) { this.cost = cost; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
