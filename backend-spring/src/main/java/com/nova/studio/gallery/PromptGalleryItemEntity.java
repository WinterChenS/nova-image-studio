package com.nova.studio.gallery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.nova.studio.infra.JsonbTypeHandler;

import java.time.Instant;

/**
 * WIN-42 (T15, ADR-40) — MyBatis-Plus entity for {@code prompt_gallery_items}
 * （提示广场入库，全局数据无 user_id）：id = source-uniqueKey，同步幂等 upsert。
 */
@TableName("prompt_gallery_items")
public class PromptGalleryItemEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;                       // uniqueKey（source-源内 id）
    @TableField("source")
    private String source;
    @TableField("source_url")
    private String sourceUrl;
    @TableField("title")
    private String title;
    @TableField("content")
    private String content;
    @TableField(value = "images", typeHandler = JsonbTypeHandler.class)
    private String images;                   // 图片 URL 列表 JSONB
    @TableField(value = "tags", typeHandler = JsonbTypeHandler.class)
    private String tags;                     // 标签列表 JSONB
    @TableField("category")
    private String category;
    @TableField("contributor")
    private String contributor;
    @TableField("notes")
    private String notes;
    @TableField(value = "raw_snapshot", typeHandler = JsonbTypeHandler.class)
    private String rawSnapshot;              // 源原始数据快照（排障/重解析）
    @TableField("synced_at")
    private Instant syncedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getImages() {
        return images;
    }

    public void setImages(String images) {
        this.images = images;
    }

    public String getTags() {
        return tags;
    }

    public void setTags(String tags) {
        this.tags = tags;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getContributor() {
        return contributor;
    }

    public void setContributor(String contributor) {
        this.contributor = contributor;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getRawSnapshot() {
        return rawSnapshot;
    }

    public void setRawSnapshot(String rawSnapshot) {
        this.rawSnapshot = rawSnapshot;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(Instant syncedAt) {
        this.syncedAt = syncedAt;
    }
}
