package com.nova.studio.asset;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * WIN-22 — MyBatis-Plus entity for {@code assets} (server-side asset metadata,
 * replacing the frontend IndexedDB store as the primary store). Binary data
 * lives in object storage ({@code storage_key}), text assets keep
 * {@code storage_key} NULL. {@code projectId} NULL = 「未分类」virtual bucket
 * (G-4, historical/migration data only).
 */
@TableName("assets")
public class AssetEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private String userId;
    @TableField("project_id")
    private String projectId;
    @TableField("kind")
    private String kind;                 // image | text
    @TableField("name")
    private String name;
    @TableField("mime_type")
    private String mimeType;
    @TableField("size_bytes")
    private Long sizeBytes;
    @TableField("width")
    private Integer width;
    @TableField("height")
    private Integer height;
    @TableField(value = "tags", typeHandler = com.nova.studio.infra.JsonbTypeHandler.class)
    private String tags;                 // JSONB array kept as string + service-layer serialize (B-4: jsonb TypeHandler)
    @TableField("note")
    private String note;
    @TableField("source_kind")
    private String sourceKind;           // 9-value AssetSourceKind enum
    @TableField("source_label")
    private String sourceLabel;
    @TableField("source_ref")
    private String sourceRef;
    @TableField("prompt")
    private String prompt;
    @TableField("storage_key")
    private String storageKey;
    @TableField("hash")
    private String hash;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;
    @TableField("last_used_at")
    private Instant lastUsedAt;

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

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(Long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public Integer getWidth() {
        return width;
    }

    public void setWidth(Integer width) {
        this.width = width;
    }

    public Integer getHeight() {
        return height;
    }

    public void setHeight(Integer height) {
        this.height = height;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    public String getSourceLabel() {
        return sourceLabel;
    }

    public void setSourceLabel(String sourceLabel) {
        this.sourceLabel = sourceLabel;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
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

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }
}
