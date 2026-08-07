package com.nova.studio.rbac;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.UUID;

/**
 * WIN-25 (V5) — MyBatis-Plus entity for {@code role_permissions}
 * (PK (role_id, permission_id), ON DELETE CASCADE).
 */
@TableName("role_permissions")
public class RolePermissionEntity {

    @TableId(value = "role_id")
    private UUID roleId;
    @TableField("permission_id")
    private UUID permissionId;

    public UUID getRoleId() { return roleId; }
    public void setRoleId(UUID roleId) { this.roleId = roleId; }
    public UUID getPermissionId() { return permissionId; }
    public void setPermissionId(UUID permissionId) { this.permissionId = permissionId; }
}
