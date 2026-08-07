package com.nova.studio.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-29 (V5) — MyBatis-Plus entity for {@code audit_log} (T29/A16): change
 * audit for account/pricing/role/permission mutations, same retention as
 * {@code usage_records}. Detail is a JSONB summary (never keys/credentials).
 */
@TableName("audit_log")
public class AuditLogEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("actor_id")
    private UUID actorId;
    @TableField("action")
    private String action;
    @TableField("target_type")
    private String targetType;
    @TableField("target_id")
    private String targetId;
    @TableField("detail")
    private String detail;
    @TableField("created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public UUID getActorId() { return actorId; }
    public void setActorId(UUID actorId) { this.actorId = actorId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
