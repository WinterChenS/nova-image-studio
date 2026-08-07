package com.nova.studio.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/** WIN-25 (V5) — MyBatis-Plus mapper for {@code role_permissions}. */
@Mapper
public interface RolePermissionMapper extends BaseMapper<RolePermissionEntity> {

    /** Permission codes granted to a role (matrix UI, T28). */
    @Select("""
            SELECT p.code FROM role_permissions rp
            JOIN permissions p ON p.id = rp.permission_id
            WHERE rp.role_id = #{roleId}
            ORDER BY p.sort_order
            """)
    List<String> permissionCodesByRoleId(@Param("roleId") UUID roleId);
}
