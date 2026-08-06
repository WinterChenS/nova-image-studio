package com.nova.studio.project;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * WIN-22 — MyBatis-Plus entity for {@code projects} (per-user private project).
 * {@code id} is code-generated (UUID string, {@code IdType.INPUT}), matching the
 * {@code tasks.id} TEXT style. {@code autoSave} (F-8, P1) controls whether
 * generation results auto-enter this project's assets.
 */
@TableName("projects")
public class ProjectEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private String userId;
    @TableField("name")
    private String name;
    @TableField("description")
    private String description;
    @TableField("archived")
    private Boolean archived;
    @TableField("sort_order")
    private Integer sortOrder;
    @TableField("auto_save")
    private Boolean autoSave;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Boolean getArchived() {
        return archived;
    }

    public void setArchived(Boolean archived) {
        this.archived = archived;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Boolean getAutoSave() {
        return autoSave;
    }

    public void setAutoSave(Boolean autoSave) {
        this.autoSave = autoSave;
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
