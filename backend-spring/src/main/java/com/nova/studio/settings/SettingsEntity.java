package com.nova.studio.settings;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-16 (ADR-11) — MyBatis-Plus entity for {@code settings}. Composite
 * primary key ({@code user_id + key}) is not modeled by MyBatis-Plus, so the
 * repository uses wrapper-based ops and the annotated {@code ON CONFLICT}
 * upsert (ARCH C.3.2.5). The {@code value} JSONB column stays a String.
 */
@TableName("settings")
public class SettingsEntity {

    @TableField("user_id")
    private UUID userId;
    @TableField("key")
    private String key;
    @TableField("value")
    private String value;
    @TableField("value_type")
    private String valueType;
    @TableField("description")
    private String description;
    @TableField("updated_at")
    private Instant updatedAt;

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getValueType() {
        return valueType;
    }

    public void setValueType(String valueType) {
        this.valueType = valueType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
