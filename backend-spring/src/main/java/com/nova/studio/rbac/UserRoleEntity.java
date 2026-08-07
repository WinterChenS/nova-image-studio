package com.nova.studio.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

/**
 * WIN-25 (V5) — MyBatis-Plus entity for {@code user_roles}
 * (PK (user_id, role_id), ON DELETE CASCADE; 多行支持多角色扩展 A15).
 */
@TableName("user_roles")
public class UserRoleEntity {

    @TableId(value = "user_id")
    private UUID userId;
    @TableField("role_id")
    private UUID roleId;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }
}
