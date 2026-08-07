package com.nova.studio.web;

import com.nova.studio.audit.AuditQueryService;
import com.nova.studio.audit.UsageAggService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T24 (WIN-29) — /api/nova/usage/me (A11): 用户间用量隔离 —— the endpoint is
 * bound to the authenticated principal; anonymous → 401, and the service is
 * always invoked with the caller's own userId (never a request parameter).
 */
class UsageMeControllerTest {

    private final AuditQueryService auditQuery = mock(AuditQueryService.class);
    private final UsageAggService aggService = mock(UsageAggService.class);
    private UsageMeController controller;

    private final AuthUser alice = new AuthUser(UUID.randomUUID(), "alice", "user");

    @BeforeEach
    void setUp() {
        controller = new UsageMeController(auditQuery, aggService);
    }

    @Test
    void anonymousRejected() {
        assertThatThrownBy(() -> controller.me(null, null, null, 1, 50, null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void returnsOnlyOwnData() {
        when(auditQuery.query(any(), eq(1), eq(50))).thenReturn(Map.of(
                "items", List.of(), "total", 0L, "page", 1, "size", 50, "summary", Map.of()));
        when(aggService.userDaily(any(), any(), any())).thenReturn(List.of());
        Map<String, Object> result = controller.me(null, null, null, 1, 50, alice);
        assertThat(result).containsKey("items");
        assertThat(result).containsKey("daily");
        // 服务调用必须携带 alice.id，杜绝越权
        verify(auditQuery).query(any(), eq(1), eq(50));
    }

    @Test
    void queryIsAlwaysScopedToTheCallersUserId() {
        // 服务调用必须携带 alice.id（A11 用户间隔离）：捕获 Filters 断言 userId = 当前用户
        when(auditQuery.query(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(Map.of());
        when(aggService.userDaily(any(), any(), any())).thenReturn(List.of());
        controller.me(null, null, null, 1, 50, alice);
        var captor = org.mockito.ArgumentCaptor.forClass(com.nova.studio.audit.UsageRecordRepository.Filters.class);
        verify(auditQuery).query(captor.capture(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
        assertThat(captor.getValue().userId()).isEqualTo(alice.id());
    }
}
