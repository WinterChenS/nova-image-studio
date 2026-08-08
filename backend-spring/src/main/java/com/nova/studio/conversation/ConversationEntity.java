package com.nova.studio.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nova.studio.infra.JsonbTypeHandler;

import java.time.Instant;

/**
 * WIN-39 (WIN-40 T3) — MyBatis-Plus entity for {@code conversations}
 * (Agent 多会话实体，C1). {@code pending} 一次仅承载一个进行中状态
 * （pendingProposal 或 pendingGeneration，FR-1.2 中断恢复）；{@code contextSummary}
 * 为自动上下文压缩摘要（ADR-44，阶段2 写入，阶段1 读写就绪）。
 */
@TableName("conversations")
public class ConversationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private String userId;
    @TableField("title")
    private String title;
    @TableField("status")
    private String status;                       // active|archived|deleted
    @TableField("image_model")
    private String imageModel;
    @TableField("web_search")
    private Boolean webSearch;
    @TableField(value = "pending", typeHandler = JsonbTypeHandler.class)
    private String pending;                      // JSONB {kind:'proposal'|'generation', ...}
    @TableField(value = "context_summary", typeHandler = JsonbTypeHandler.class)
    private String contextSummary;               // JSONB {text,model,foldedBeforeMessageId,foldedCount,foldedAt}
    @TableField("deleted_at")
    private Instant deletedAt;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;
    @TableField("last_message_at")
    private Instant lastMessageAt;

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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getImageModel() {
        return imageModel;
    }

    public void setImageModel(String imageModel) {
        this.imageModel = imageModel;
    }

    public Boolean getWebSearch() {
        return webSearch;
    }

    public void setWebSearch(Boolean webSearch) {
        this.webSearch = webSearch;
    }

    public String getPending() {
        return pending;
    }

    public void setPending(String pending) {
        this.pending = pending;
    }

    public String getContextSummary() {
        return contextSummary;
    }

    public void setContextSummary(String contextSummary) {
        this.contextSummary = contextSummary;
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

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public void setLastMessageAt(Instant lastMessageAt) {
        this.lastMessageAt = lastMessageAt;
    }
}
