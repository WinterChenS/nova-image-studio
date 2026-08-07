package com.nova.studio.audit;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.UUID;

/**
 * WIN-29 (V5) — MyBatis-Plus mapper for {@code audit_log} (T29/A16). The
 * INSERT uses an explicit SQL with {@code ::jsonb} cast for the detail column
 * (BaseMapper's generic insert would send varchar and PG rejects it).
 * Retention cleanup through {@link AuditLogRepository#deleteBefore}.
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {

    @Insert("""
            INSERT INTO audit_log (actor_id, action, target_type, target_id, detail, created_at)
            VALUES (#{actorId}, #{action}, #{targetType}, #{targetId}, #{detail}::jsonb, #{createdAt})
            """)
    int insertLog(@Param("actorId") UUID actorId, @Param("action") String action,
                  @Param("targetType") String targetType, @Param("targetId") String targetId,
                  @Param("detail") String detail, @Param("createdAt") Instant createdAt);
}
