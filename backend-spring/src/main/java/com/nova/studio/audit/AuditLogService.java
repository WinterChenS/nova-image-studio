package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * T29 (WIN-29) — audit_log service (A16): synchronous change-audit writes for
 * account / pricing / role-permission / user-role mutations. The detail JSON
 * is a summarized key-value map — callers must never pass keys/credentials
 * (interface contract; masked values only).
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogMapper mapper;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogMapper mapper) {
        this.mapper = mapper;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Record one change event. {@code actorId} null (system-triggered, e.g.
     * cap auto-pause) is allowed; {@code detail} is stored as JSONB.
     */
    public void record(UUID actorId, String action, String targetType, String targetId, Map<String, Object> detail) {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setActorId(actorId);
        entity.setAction(action);
        entity.setTargetType(targetType);
        entity.setTargetId(targetId);
        entity.setDetail(toJson(detail == null ? Map.of() : detail));
        entity.setCreatedAt(Instant.now());
        try {
            mapper.insertLog(entity.getActorId(), entity.getAction(), entity.getTargetType(),
                    entity.getTargetId(), entity.getDetail(), entity.getCreatedAt());
        } catch (Exception e) {
            // 审计失败不影响业务主链路（日志告警，A16 尽力而为）
            log.warn("[audit-log] 写入失败 action={} target={}/{}: {}", action, targetType, targetId, e.getMessage());
        }
    }

    private String toJson(Map<String, Object> detail) {
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (Exception e) {
            log.warn("[audit-log] detail 序列化失败，降级为空对象: {}", e.getMessage());
            return "{}";
        }
    }
}
