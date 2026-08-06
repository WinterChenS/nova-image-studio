package com.nova.studio.gallery;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * T3.1 — MyBatis-Plus entity for {@code blacklist} (sensitive keywords,
 * DB-ized from {@code backend/blacklist.json}). {@code keyword} is UNIQUE so
 * seed and admin adds are idempotent.
 */
@TableName("blacklist")
public class BlacklistEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private UUID id;
    @TableField("keyword")
    private String keyword;
    @TableField("created_at")
    private Instant createdAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
