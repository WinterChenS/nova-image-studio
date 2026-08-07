package com.nova.studio.history;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nova.studio.infra.JsonbTypeHandler;

import java.time.Instant;

/**
 * WIN-39 (ADR-39) — MyBatis-Plus entity for {@code histories}（统一历史表：
 * type=reverse|gif，status 语义见 DDL；image_ids 引用 assets.id）。
 */
@TableName("histories")
public class HistoryEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private String userId;
    @TableField("type")
    private String type;                       // reverse | gif
    @TableField("status")
    private String status;
    @TableField("title")
    private String title;
    @TableField(value = "payload", typeHandler = JsonbTypeHandler.class)
    private String payload;
    @TableField(value = "image_ids", typeHandler = JsonbTypeHandler.class)
    private String imageIds;
    @TableField("task_id")
    private String taskId;
    @TableField("error")
    private String error;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getImageIds() {
        return imageIds;
    }

    public void setImageIds(String imageIds) {
        this.imageIds = imageIds;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
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
