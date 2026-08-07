package com.nova.studio.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * T29 (WIN-29) — audit_log service: account/price/role/permission changes are
 * logged with actor/action/target + JSON detail (never keys/credentials, A16).
 */
class AuditLogServiceTest {

    private AuditLogRepository repository;
    private AuditLogService service;

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        repository = mock(AuditLogRepository.class);
        service = new AuditLogService(repository, new ObjectMapper());
    }

    @Test
    void logPersistsActorActionTargetAndJsonDetail() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", "Gemini-主账号");
        detail.put("status", "active");

        service.log(ACTOR, "account.create", "ai_accounts", "acc-1", detail);

        verify(repository).insert(eq(ACTOR), eq("account.create"), eq("ai_accounts"), eq("acc-1"),
                org.mockito.ArgumentMatchers.matches("\\{\"name\":\"Gemini-主账号\",\"status\":\"active\"\\}"),
                any(Instant.class));
    }

    @Test
    void logWithNullDetailStoresEmptyJsonObject() {
        service.log(ACTOR, "account.pause", "ai_accounts", "acc-1", null);
        verify(repository).insert(eq(ACTOR), eq("account.pause"), eq("ai_accounts"), eq("acc-1"),
                eq("{}"), any(Instant.class));
    }

    /** A16 + PRD §7: audit detail must never carry keys/credentials. */
    @Test
    void logSerializesDetailWithoutCredentials() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", "账号A");
        detail.put("apiKey", "sk-should-not-leak");
        String json = service.toJson(detail);
        assertThat(json).contains("账号A").doesNotContain("sk-should-not-leak");
    }
}
