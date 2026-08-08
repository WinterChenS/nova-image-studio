package com.nova.studio.conversation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nova.studio.infra.JsonbTypeHandler;

import java.time.Instant;

/**
 * WIN-39 (WIN-40 T3) — MyBatis-Plus entity for {@code conversation_messages}.
 * {@code imageIds} 引用 assets.id（统一素材，ADR-36，不再独立 conversation_images 表）；
 * {@code proposalData} 为已确认提案（重编辑用）；{@code withdrawable} 撤回标志。
 */
@TableName("conversation_messages")
public class ConversationMessageEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("user_id")
    private String userId;
    @TableField("role")
    private String role;                         // user|assistant|system-note|context-divider
    @TableField("text")
    private String text;
    @TableField("reasoning")
    private String reasoning;
    @TableField(value = "image_ids", typeHandler = JsonbTypeHandler.class)
    private String imageIds;                     // assets.id 列表
    @TableField("task_id")
    private String taskId;
    @TableField(value = "proposal_data", typeHandler = JsonbTypeHandler.class)
    private String proposalData;
    @TableField("web_search_used")
    private Boolean webSearchUsed;
    @TableField("withdrawable")
    private Boolean withdrawable;
    @TableField("created_at")
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getReasoning() {
        return reasoning;
    }

    public void setReasoning(String reasoning) {
        this.reasoning = reasoning;
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

    public String getProposalData() {
        return proposalData;
    }

    public void setProposalData(String proposalData) {
        this.proposalData = proposalData;
    }

    public Boolean getWebSearchUsed() {
        return webSearchUsed;
    }

    public void setWebSearchUsed(Boolean webSearchUsed) {
        this.webSearchUsed = webSearchUsed;
    }

    public Boolean getWithdrawable() {
        return withdrawable;
    }

    public void setWithdrawable(Boolean withdrawable) {
        this.withdrawable = withdrawable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
