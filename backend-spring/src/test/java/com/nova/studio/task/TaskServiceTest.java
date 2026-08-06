package com.nova.studio.task;

import com.nova.studio.auth.AuthUser;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.settings.ModelService;
import com.nova.studio.project.ProjectService;
import com.nova.studio.settings.SettingsService;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
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
 * T1.1/T1.2/T1.10 + M2 T2.2/T2.4 — task create flow: login required (Q1),
 * validation messages, 503 not-accepting, dual-dimension rate limit 429, queue
 * capacity 503/429, server-side model resolution (T2.4), and the happy path
 * insert → register → enqueue.
 */
class TaskServiceTest {

    private TaskRepository repository;
    private TaskQueueService queueService;
    private QueueStatsService queueStatsService;
    private RateLimiterService rateLimiter;
    private ShutdownFlag shutdownFlag;
    private ModelService modelService;
    private TaskService taskService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
        modelService = mock(ModelService.class);
        SettingsService settingsService = mock(SettingsService.class);
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.defaultProjectId(USER_ID)).thenReturn("default-project");
        when(projectService.owns(eq(USER_ID), anyString())).thenReturn(true);
        taskService = new TaskService(repository, queueService, queueStatsService, rateLimiter,
                shutdownFlag, imageStorageService, broadcaster, lookupService,
                modelService, settingsService, projectService, MAPPER, new TaskMetrics(new SimpleMeterRegistry()), 120_000, 43_200_000);
    }

    private void defaults() {
        when(queueStatsService.getLimitConfig(any(UUID.class))).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 20, 20, 20, 10, 30));
        when(rateLimiter.consume(anyString(), any(Integer.class), any(Long.class))).thenReturn(0L);
    }

    private ObjectNode validBody() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("apiKey", "sk-test");
        body.put("baseUrl", "https://api.openai.com/v1");
        body.put("protocol", "openai");
        body.put("mode", "text-to-image");
        body.put("prompt", "a cat");
        body.put("model", "gpt-image-1");
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
    void validatesModeWithNodeMessages() {
        defaults();
        ObjectNode body2 = validBody();
        body2.put("mode", "invalid");
        assertThatThrownBy(() -> taskService.createTask(body2, "1.2.3.4", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("任务模式无效");
    }

    @Test
    void rejectsUnknownModelWhenNoLegacyInputs() {
        defaults();
        when(modelService.resolve(eq(USER_ID), anyString())).thenReturn(Optional.empty());
        ObjectNode body = validBody();
        body.remove("apiKey");
        body.remove("baseUrl");
        body.remove("protocol");
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("未找到模型配置");
    }

    @Test
    void resolvesModelServerSideAndUsesResolvedConfig() {
        defaults();
        UUID modelUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(modelService.resolve(eq(USER_ID), eq(modelUuid.toString()))).thenReturn(Optional.of(
                new ModelService.ResolvedModel(modelUuid, "image", "openai", "GPT Image 2",
                        "gpt-image-2", "https://api.openai.com", "sk-resolved", "gpt-image-2")));
        ObjectNode body = validBody();
        body.put("model", modelUuid.toString());
        body.remove("apiKey");
        body.remove("baseUrl");
        body.remove("protocol");
        body.put("modelId", "gpt-image-2");

        String taskId = taskService.createTask(body, "10.0.0.1", USER);

        assertThat(taskId).isNotBlank();
        verify(repository).insertTaskAndItems(anyString(), eq(USER_ID), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(Integer.class));
        verify(queueService).registerRuntimeState(anyString(), eq("sk-resolved"), any(), any());
        verify(queueService).enqueue(taskId);
    }

    @Test
    void rejectsIncompleteResolvedModel() {
        defaults();
        UUID modelUuid = UUID.fromString("22222222-2222-2222-2222-222222222222");
        when(modelService.resolve(eq(USER_ID), eq(modelUuid.toString()))).thenReturn(Optional.of(
                new ModelService.ResolvedModel(modelUuid, "image", "openai", "GPT Image 2",
                        "gpt-image-2", "https://api.openai.com", null, "gpt-image-2")));
        ObjectNode body = validBody();
        body.put("model", modelUuid.toString());
        body.remove("apiKey");
        body.remove("baseUrl");
        body.remove("protocol");
        assertThatThrownBy(() -> taskService.createTask(body, "10.0.0.1", USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模型配置不完整");
    }

    @Test
    void rejectsWhenNotAcceptingNewTasks() {
        when(shutdownFlag.isShuttingDown()).thenReturn(true);
        when(queueStatsService.getLimitConfig(any(UUID.class))).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 20, 20, 20, 10, 30));
        ObjectNode body = validBody();
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("SERVER_NOT_ACCEPTING_TASKS");
                });
        verify(queueService, never()).enqueue(anyString());
    }

    @Test
    void rateLimitReturns429WithRetryAfter() {
        when(queueStatsService.getLimitConfig(any(UUID.class))).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 2, 2, 20, 10, 30));
        when(rateLimiter.consume(anyString(), any(Integer.class), any(Long.class)))
                .thenReturn(0L)
                .thenReturn(45L);
        ObjectNode body = validBody();
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(429);
                    assertThat(e.getCode()).isEqualTo("RATE_LIMITED");
                    assertThat(e.getRetryAfter()).isEqualTo(45);
                });
    }

    @Test
    void queueFullReturns503() {
        when(queueStatsService.getLimitConfig(any(UUID.class))).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 20, 20, 20, 10, 30));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pendingCount", 200L);
        when(queueStatsService.getQueueStatus()).thenReturn(stats);
        ObjectNode body = validBody();
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4", USER))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("QUEUE_FULL");
                });
    }

    @Test
    void happyPathLegacyInputsInsertsRegistersAndEnqueues() {
        defaults();
        ObjectNode body = validBody();
        String taskId = taskService.createTask(body, "10.0.0.1", USER);
        assertThat(taskId).isNotBlank();
        verify(repository).insertTaskAndItems(anyString(), eq(USER_ID), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(Integer.class));
        verify(queueService).registerRuntimeState(anyString(), eq("sk-test"), any(), any());
        verify(queueService).enqueue(taskId);
    }

    @Test
    void hashApiKeyProduces24CharSha256Prefix() {
        assertThat(TaskService.hashApiKey("sk-test")).hasSize(24);
    }
}
