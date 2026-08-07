package com.nova.studio.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T29 (WIN-29) — change audit service (A16): account / pricing / role /
 * permission changes are appended to {@code audit_log} with actor, action,
 * target and a JSON detail summary. Credentials are never logged — detail keys
 * matching a credential pattern are dropped defensively (PRD §7 安全：
 * 审计明细不含 Key), and callers must not include key material to begin with.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    /** Credential-ish detail keys are dropped before persistence. */
    private static final java.util.regex.Pattern CREDENTIAL_KEY =
            java.util.regex.Pattern.compile("(?i)(api[_-]?key|secret|password|token|credential|私钥|密钥)");

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /** Record one change (detail may be null → empty object). Never throws on write. */
    public void log(UUID actorId, String action, String targetType, String targetId,
                    Map<String, Object> detail) {
        try {
            repository.insert(actorId, action, targetType, targetId, toJson(detail), Instant.now());
        } catch (Exception e) {
            // audit is best-effort — never break the business path it instruments
            log.warn("[audit-log] 变更审计写入失败 {}:{} — {}", targetType, targetId, e.getMessage());
        }
    }

    /** Serialize detail, dropping credential keys (defense-in-depth, A16). */
    public String toJson(Map<String, Object> detail) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (detail != null) {
            detail.forEach((k, v) -> {
                if (!CREDENTIAL_KEY.matcher(k).find()) {
                    safe.put(k, v);
                }
            });
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (Exception e) {
            log.warn("[audit-log] detail 序列化失败，回退空对象: {}", e.getMessage());
            return "{}";
        }
    }
}
