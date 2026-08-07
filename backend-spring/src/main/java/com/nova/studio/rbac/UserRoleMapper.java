package com.nova.studio.rbac;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

/**
 * WIN-25 (V5) — MyBatis-Plus mapper for {@code user_roles}. Join queries for
 * the RBAC read path (user_roles → role_permissions → permissions, R4).
 */
@Mapper
public interface UserRoleMapper extends BaseMapper<UserRoleEntity> {

    /** Role codes for a user (empty when the user has no rows — service falls back to users.role). */
    @Select("""
            SELECT r.code FROM user_roles ur
            JOIN roles r ON r.id = ur.role_id
            WHERE ur.user_id = #{userId}
            """)
    List<String> roleCodesByUserId(@Param("userId") UUID userId);

    /** Distinct permission codes for a set of role ids (empty input → empty result). */
    @Select("""
            <script>
            SELECT DISTINCT p.code FROM role_permissions rp
            JOIN permissions p ON p.id = rp.permission_id
            WHERE rp.role_id IN
            <foreach collection="roleIds" item="rid" open="(" separator="," close=")">#{rid}</foreach>
            </script>
            """)
    List<String> permissionCodesByRoleIds(@Param("roleIds") List<UUID> roleIds);

    /** Role id for a code (admin|user). */
    @Select("SELECT id FROM roles WHERE code = #{code}")
    UUID roleIdByCode(@Param("code") String code);
}
