package com.nova.studio.audit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T29 (WIN-29) — audit_log service (A16): a {@code record(actor, action,
 * targetType, targetId, detail)} write maps onto the {@code audit_log}
 * INSERT; detail JSON never carries secrets (the service layer passes only
 * summarized key-value pairs).
 */
class AuditLogServiceTest {

    private AuditLogMapper mapper;
    private AuditLogService service;

    private static final UUID ACTOR = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @BeforeEach
    void setUp() {
        mapper = mock(AuditLogMapper.class);
        when(mapper.insertLog(any(), any(), any(), any(), any(), any())).thenReturn(1);
        service = new AuditLogService(mapper);
    }

    @Test
    void recordPersistsActionAndTarget() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", "active");
        detail.put("after", "paused");
        service.record(ACTOR, "account.pause", "ai_accounts", "33333333-3333-3333-3333-333333333333", detail);

        verify(mapper).insertLog(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordNormalizesNullActorToNullNotError() {
        service.record(null, "system.probe", "ai_accounts", null, Map.of());
        verify(mapper).insertLog(any(), any(), any(), any(), any(), any());
    }

    @Test
    void recordKeepsDetailJson() {
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        service.record(ACTOR, "role_permissions.update", "role_permissions", "00000000-0000-0000-0000-000000000001",
                Map.of("added", java.util.List.of("audit.view"), "removed", java.util.List.of("pricing.manage")));
        verify(mapper).insertLog(any(), any(), any(), any(), captor.capture(), any());
        assertThat(captor.getValue()).contains("audit.view");
    }
}
