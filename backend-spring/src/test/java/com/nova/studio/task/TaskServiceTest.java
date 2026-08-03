package com.nova.studio.task;

import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;

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
 * T1.1/T1.2/T1.10 — task create flow: validation (Node validateCreatePayload
 * messages), 503 not-accepting, dual-dimension rate limit 429, queue capacity
 * 503/429, and the happy path insert → register → enqueue.
 */
class TaskServiceTest {

    private TaskRepository repository;
    private TaskQueueService queueService;
    private QueueStatsService queueStatsService;
    private RateLimiterService rateLimiter;
    private ShutdownFlag shutdownFlag;
    private TaskService taskService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        taskService = new TaskService(repository, queueService, queueStatsService, rateLimiter,
                shutdownFlag, imageStorageService, broadcaster, lookupService, MAPPER, 120_000, 43_200_000);
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
    void validatesProtocolAndModeWithNodeMessages() {
        ObjectNode body = validBody();
        body.put("protocol", "nope");
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("协议类型无效，必须为 google、openai 或 grok");

        ObjectNode body2 = validBody();
        body2.put("mode", "invalid");
        assertThatThrownBy(() -> taskService.createTask(body2, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("任务模式无效");
    }

    @Test
    void rejectsMissingApiKey() {
        ObjectNode body = validBody();
        body.remove("apiKey");
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("缺少 API 密钥");
    }

    @Test
    void rejectsWhenNotAcceptingNewTasks() {
        when(shutdownFlag.isShuttingDown()).thenReturn(true);
        when(queueStatsService.getLimitConfig()).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 20, 20, 20, 10, 30));
        ObjectNode body = validBody();
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("SERVER_NOT_ACCEPTING_TASKS");
                });
        verify(queueService, never()).enqueue(anyString());
    }

    @Test
    void rateLimitReturns429WithRetryAfter() {
        when(queueStatsService.getLimitConfig()).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 2, 2, 20, 10, 30));
        when(rateLimiter.consume(anyString(), any(Integer.class), any(Long.class)))
                .thenReturn(0L)
                .thenReturn(45L);
        ObjectNode body = validBody();
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(429);
                    assertThat(e.getCode()).isEqualTo("RATE_LIMITED");
                    assertThat(e.getRetryAfter()).isEqualTo(45);
                });
    }

    @Test
    void queueFullReturns503() {
        when(queueStatsService.getLimitConfig()).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 20, 20, 20, 10, 30));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pendingCount", 200L);
        when(queueStatsService.getQueueStatus()).thenReturn(stats);
        ObjectNode body = validBody();
        assertThatThrownBy(() -> taskService.createTask(body, "1.2.3.4"))
                .isInstanceOfSatisfying(HttpErrorException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(503);
                    assertThat(e.getCode()).isEqualTo("QUEUE_FULL");
                });
    }

    @Test
    void happyPathInsertsRegistersAndEnqueues() {
        when(queueStatsService.getLimitConfig()).thenReturn(
                new QueueStatsService.LimitConfig(200, 60_000, 20, 20, 20, 10, 30));
        when(rateLimiter.consume(anyString(), any(Integer.class), any(Long.class))).thenReturn(0L);
        ObjectNode body = validBody();
        String taskId = taskService.createTask(body, "10.0.0.1");
        assertThat(taskId).isNotBlank();
        verify(repository).insertTaskAndItems(anyString(), any(), anyString(), anyString(),
                anyString(), anyString(), any(Integer.class));
        verify(queueService).registerRuntimeState(anyString(), eq("sk-test"), any(), any());
        verify(queueService).enqueue(taskId);
    }

    @Test
    void hashApiKeyProduces24CharSha256Prefix() {
        assertThat(TaskService.hashApiKey("sk-test")).hasSize(24);
    }
}
