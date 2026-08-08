package com.nova.studio.canvas;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nova.studio.infra.JsonbTypeHandler;

import java.time.Instant;

/**
 * WIN-39 (WIN-40 T5) — MyBatis-Plus entity for {@code canvas_projects}
 * （结构文档化 + 素材引用化，ADR-35）：nodes/connections/viewport 整文档 JSONB，
 * 节点图片引用 assets.id（imageRef=assetId）。{@code version} 冲突检测（A8，阶段3 T16 启用校验）。
 */
@TableName("canvas_projects")
public class CanvasProjectEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private String userId;
    @TableField("title")
    private String title;
    @TableField(value = "nodes", typeHandler = JsonbTypeHandler.class)
    private String nodes;                    // CanvasNodeData[]
    @TableField(value = "connections", typeHandler = JsonbTypeHandler.class)
    private String connections;              // CanvasConnection[]
    @TableField("background_mode")
    private String backgroundMode;
    @TableField("show_image_info")
    private Boolean showImageInfo;
    @TableField(value = "viewport", typeHandler = JsonbTypeHandler.class)
    private String viewport;                 // {x,y,k}
    @TableField("version")
    private Long version;
    @TableField("deleted_at")
    private Instant deletedAt;
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getNodes() {
        return nodes;
    }

    public void setNodes(String nodes) {
        this.nodes = nodes;
    }

    public String getConnections() {
        return connections;
    }

    public void setConnections(String connections) {
        this.connections = connections;
    }

    public String getBackgroundMode() {
        return backgroundMode;
    }

    public void setBackgroundMode(String backgroundMode) {
        this.backgroundMode = backgroundMode;
    }

    public Boolean getShowImageInfo() {
        return showImageInfo;
    }

    public void setShowImageInfo(Boolean showImageInfo) {
        this.showImageInfo = showImageInfo;
    }

    public String getViewport() {
        return viewport;
    }

    public void setViewport(String viewport) {
        this.viewport = viewport;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
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
