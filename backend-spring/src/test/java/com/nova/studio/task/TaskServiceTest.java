package com.nova.studio.task;

import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.project.ProjectService;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5/T12 (WIN-28) — task create flow on the account pool: body {@code model}
 * is a catalog UUID, resolved against the global catalog with an account
 * pre-check (A5); rate limiting is per-IP + per-USER (ADR-27, apiKeyHash → 
 * userId); happy path inserts → registers → enqueues. The old per-user
 * model resolution path is gone (Q1 直接移除).
 */
class TaskServiceTest {

    private TaskRepository repository;
    private TaskQueueService queueService;
    private QueueStatsService queueStatsService;
    private RateLimiterService rateLimiter;
    private ShutdownFlag shutdownFlag;
    private CatalogModelService catalogModelService;
    private AccountService accountService;
    private TaskService taskService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AuthUser USER = new AuthUser(USER_ID, "alice", "user");

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        queueService = mock(TaskQueueService.class);
        queueStatsService = mock(QueueStatsService.class);
        rateLimiter = mock(RateLimiterService.class);
        shutdownFlag = mock(ShutdownFlag.class);
        ImageStorageService imageStorageService = mock(ImageStorageService.class);
        TaskEventBroadcaster broadcaster = mock(TaskEventBroadcaster.class);
        TaskLookupService lookupService = mock(TaskLookupService.class);
        catalogModelService = mock(CatalogModelService.class);
        accountService = mock(AccountService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.defaultProjectId(USER_ID)).thenReturn("default-project");
        when(projectService.owns(eq(USER_ID), anyString())).thenReturn(true);
        when(catalogModelService.resolve(MODEL_ID)).thenReturn(Optional.of(catalogRow(true)));
        when(accountService.hasCandidate(any())).thenReturn(true);
        taskService = new TaskService(repository, queueService, queueStatsService, rateLimiter,
                shutdownFlag, imageStorageService, broadcaster, lookupService,
                catalogModelService, accountService, settingsService, projectService, MAPPER,
                new TaskMetrics(new SimpleMeterRegistry()), 120_000, 43_200_000);
    }

    private CatalogModelRepository.Row catalogRow(boolean enabled) {
        return new CatalogModelRepository.Row(MODEL_ID, "image", "openai", "模型", "gpt-image-1",
                "https://api.example.com/v1", "{}", null, enabled, null,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    private void defaults() {
        when(queueStatsService.getLimitConfig(any(UUID.class))).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 20, 20, 20, 10, 30));
        when(rateLimiter.consume(anyString(), any(Integer.class), any(Long.class))).thenReturn(0L);
    }

    private ObjectNode validBody() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("mode", "text-to-image");
        body.put("prompt", "a cat");
        body.put("model", MODEL_ID.toString());
        body.put("parallelCount", 2);
        return body;
    }

    @Test
    void rejectsAnonymousCreation() {
        defaults();
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", null))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(401);
                    assertThat(e.getCode()).isEqualTo("UNAUTHORIZED");
                });
        verify(queueService, never()).enqueue(anyString());
    }

    @Test
    void rejectsUnknownCatalogModel() {
        defaults();
        ObjectNode body = validBody();
        body.put("model", UUID.randomUUID().toString());
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到模型配置");
        verify(queueService, never()).enqueue(anyString());
    }

    @Test
    void rejectsTextCatalogModel() {
        defaults();
        when(catalogModelService.resolve(MODEL_ID)).thenReturn(Optional.of(
                new CatalogModelRepository.Row(MODEL_ID, "text", "openai-chat-completions", "模型", "gpt-4o",
                        "https://api.example.com/v1", "{}", null, true, null,
                        Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"))));
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是图片模型");
    }

    @Test
    void rejectsDisabledCatalogModel() {
        defaults();
        when(catalogModelService.resolve(MODEL_ID)).thenReturn(Optional.of(catalogRow(false)));
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("禁用");
                });
    }

    @Test
    void rejectsWhenNoUsableAccount() {
        defaults();
        when(accountService.hasCandidate(any())).thenReturn(false);
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(400);
                    assertThat(e.getMessage()).contains("可用账号");
                });
    }

    @Test
    void rejectsServerNotAccepting() {
        defaults();
        when(shutdownFlag.isShuttingDown()).thenReturn(true);
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(503));
    }

    @Test
    void rateLimitUsesIpDimension() {
        defaults();
        when(rateLimiter.consume(eq("ip:1.2.3.4"), any(Integer.class), any(Long.class))).thenReturn(1L);
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(429));
        verify(rateLimiter, never()).consume(eq("user:" + USER_ID), any(Integer.class), any(Long.class));
    }

    @Test
    void rateLimitUsesUserIdDimension() {
        defaults();
        when(rateLimiter.consume(eq("user:" + USER_ID), any(Integer.class), any(Long.class))).thenReturn(1L);
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(429));
        verify(rateLimiter).consume(eq("user:" + USER_ID), any(Integer.class), any(Long.class));
    }

    @Test
    void queueCapacityEnforcedPerUser() {
        defaults();
        when(queueStatsService.getQueueStatus()).thenReturn(java.util.Map.of("pendingCount", 0));
        when(queueService.getPendingCountByUser(USER_ID.toString())).thenReturn(100);
        assertThatThrownBy(() -> taskService.createTask(validBody(), "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> assertThat(e.getStatusCode()).isEqualTo(429));
    }

    @Test
    void happyPathRegistersCatalogModelAndEnqueues() {
        defaults();

        String taskId = taskService.createTask(validBody(), "1.2.3.4", USER);

        assertThat(taskId).isNotBlank();
        verify(queueService).registerRuntimeState(eq(taskId), eq(MODEL_ID.toString()), any(), any());
        verify(queueService).enqueue(taskId);
    }

    @Test
    void requestSnapshotCarriesCatalogUuidAndUpstreamModelId() {
        defaults();
        taskService.createTask(validBody(), "1.2.3.4", USER);
        var captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(repository).insertTaskAndItems(anyString(), any(), anyString(), anyString(), anyString(),
                captor.capture(), anyString(), any(Integer.class));
        String requestJson = captor.getValue();
        assertThat(requestJson).contains("\"model\":\"" + MODEL_ID + "\"");
        assertThat(requestJson).contains("\"modelId\":\"gpt-image-1\"");
        assertThat(requestJson).doesNotContain("apiKey");
    }
}
