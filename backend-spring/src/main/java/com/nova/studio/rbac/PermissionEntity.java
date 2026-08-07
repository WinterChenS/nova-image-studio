package com.nova.studio.rbac;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-25 (V5) — MyBatis-Plus entity for {@code permissions} (权限码，type
 * ∈ menu|button，api_path 用于前端清单与「admin 端点均有权限注解」防漂移单测).
 */
@TableName("permissions")
public class PermissionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    @TableField("code")
    private String code;
    @TableField("type")
    private String type;
    @TableField("parent_code")
    private String parentCode;
    @TableField("label")
    private String label;
    @TableField("api_path")
    private String apiPath;
    @TableField("sort_order")
    private Integer sortOrder;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getParentCode() { return parentCode; }
    public void setParentCode(String parentCode) { this.parentCode = parentCode; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getApiPath() { return apiPath; }
    public void setApiPath(String apiPath) { this.apiPath = apiPath; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
