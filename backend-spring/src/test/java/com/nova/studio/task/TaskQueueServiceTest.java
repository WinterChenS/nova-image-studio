package com.nova.studio.task;

import com.nova.studio.accountpool.AccountHealthService;
import com.nova.studio.accountpool.AccountScheduler;
import com.nova.studio.accountpool.AccountService;
import com.nova.studio.accountpool.CatalogModelRepository;
import com.nova.studio.accountpool.CatalogModelService;
import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.infra.HttpErrorException;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T5 (WIN-28) — queue dispatch on the account pool: the worker selects an
 * account via {@link AccountScheduler} at generation time, tracks in-flight,
 * and retries with another account on retriable failures (≤2 retries, R3
 * boundary; 401/4xx never retried — A3/A4/A21). Per-user pending counters
 * (T12, ADR-27).
 */
class TaskQueueServiceTest {

    private TaskRepository repository;
    private ImageGenService imageGenService;
    private ImageStorageService imageStorageService;
    private TaskEventBroadcaster broadcaster;
    private QueueStatsService queueStatsService;
    private AccountScheduler scheduler;
    private AccountHealthService healthService;
    private AccountService accountService;
    private CatalogModelService catalogModelService;
    private TaskQueueService queueService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID MODEL_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ACCOUNT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID ACCOUNT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TaskRepository.TaskRow row(String id, String status, int parallelCount) {
        return new TaskRepository.TaskRow(id, USER_ID.toString(), null, status, "text-to-image",
                "{\"mode\":\"text-to-image\",\"protocol\":\"openai\",\"baseUrl\":\"http://upstream\","
                        + "\"prompt\":\"a cat\",\"model\":\"" + MODEL_ID + "\",\"modelId\":\"gpt-image-1\","
                        + "\"parallelCount\":" + parallelCount + ",\"images\":[]}",
                null, null, null, Instant.parse("2026-08-06T00:00:00Z"), null, null);
    }

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        imageGenService = mock(ImageGenService.class);
        imageStorageService = mock(ImageStorageService.class);
        broadcaster = mock(TaskEventBroadcaster.class);
        queueStatsService = mock(QueueStatsService.class);
        scheduler = mock(AccountScheduler.class);
        healthService = mock(AccountHealthService.class);
        accountService = mock(AccountService.class);
        catalogModelService = mock(CatalogModelService.class);
        when(catalogModelService.resolve(MODEL_ID)).thenReturn(Optional.of(catalogRow()));
        when(queueStatsService.getMaxServerConcurrency()).thenReturn(50);
        queueService = new TaskQueueService(repository, imageGenService, imageStorageService, broadcaster,
                MAPPER, queueStatsService, new TaskMetrics(new SimpleMeterRegistry()), scheduler,
                healthService, accountService, catalogModelService, 43_200_000, 1_800_000);
    }

    private CatalogModelRepository.Row catalogRow() {
        return new CatalogModelRepository.Row(MODEL_ID, "image", "openai", "模型", "gpt-image-1",
                "https://api.example.com/v1", "{}", null, true, null,
                Instant.parse("2026-08-06T00:00:00Z"), Instant.parse("2026-08-06T00:00:00Z"));
    }

    private AccountScheduler.SelectedAccount account(UUID id) {
        return new AccountScheduler.SelectedAccount(id, "账号-" + id, "openai", "http://upstream", "key-" + id);
    }

    private void stubSuccess(String b64) {
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class))).thenReturn(b64);
        when(imageStorageService.saveImageToDisk(anyString(), anyInt(), anyInt(), any(byte[].class), anyString()))
                .thenReturn("/api/nova/images/x/0");
    }

    // ===== dispatch selects account from pool =====

    @Test
    void taskGeneratesWithSelectedAccount() throws Exception {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", TaskRepository.STATUS_QUEUED, 1)));
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A));
        stubSuccess("QUJD");

        queueService.registerRuntimeState("t1", MODEL_ID.toString(), List.of(),
                new TaskQueueService.Source("1.2.3.4", USER_ID.toString()));
        queueService.enqueue("t1");
        awaitTerminal("t1");

        verify(imageGenService).generate(eq("openai"), eq("key-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), any(TaskRequest.class));
        verify(scheduler).release(ACCOUNT_A);
        verify(repository, atLeastOnce()).completeTask(eq("t1"), anyString(), any(), anyString(), anyString());
    }

    @Test
    void retriesOnRetriableFailureWithAnotherAccount() throws Exception {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", TaskRepository.STATUS_QUEUED, 1)));
        when(healthService.classify(any())).thenReturn(AccountHealthService.ErrorKind.SERVER_ERROR);
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A), account(ACCOUNT_B));
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class)))
                .thenThrow(new RuntimeException("500 Internal Server Error"))
                .thenReturn("QUJD");
        when(imageStorageService.saveImageToDisk(anyString(), anyInt(), anyInt(), any(byte[].class), anyString()))
                .thenReturn("/api/nova/images/x/0");

        queueService.registerRuntimeState("t1", MODEL_ID.toString(), List.of(),
                new TaskQueueService.Source("1.2.3.4", USER_ID.toString()));
        queueService.enqueue("t1");
        awaitTerminal("t1");

        verify(imageGenService, org.mockito.Mockito.times(2)).generate(anyString(), anyString(), any(TaskRequest.class));
        verify(scheduler, org.mockito.Mockito.times(2)).select(any(), any());
        verify(repository, atLeastOnce()).completeTask(eq("t1"), anyString(), any(), anyString(), anyString());
    }

    @Test
    void noRetryOn401() throws Exception {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", TaskRepository.STATUS_QUEUED, 1)));
        when(healthService.classify(any())).thenReturn(AccountHealthService.ErrorKind.UNAUTHORIZED);
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A));
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class)))
                .thenThrow(new RuntimeException("401 Unauthorized"));

        queueService.registerRuntimeState("t1", MODEL_ID.toString(), List.of(),
                new TaskQueueService.Source("1.2.3.4", USER_ID.toString()));
        queueService.enqueue("t1");
        awaitTerminal("t1");

        verify(imageGenService, org.mockito.Mockito.times(1)).generate(anyString(), anyString(), any(TaskRequest.class));
        verify(repository, atLeastOnce()).failTask(eq("t1"), anyString(), anyString(), anyString());
    }

    @Test
    void retryLimitedToTwoRetries() throws Exception {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", TaskRepository.STATUS_QUEUED, 1)));
        when(healthService.classify(any())).thenReturn(AccountHealthService.ErrorKind.SERVER_ERROR);
        when(scheduler.select(any(), any())).thenReturn(account(ACCOUNT_A), account(ACCOUNT_B), account(ACCOUNT_A));
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class)))
                .thenThrow(new RuntimeException("500 Internal Server Error"));

        queueService.registerRuntimeState("t1", MODEL_ID.toString(), List.of(),
                new TaskQueueService.Source("1.2.3.4", USER_ID.toString()));
        queueService.enqueue("t1");
        awaitTerminal("t1");

        // 最多 3 次尝试（2 次重试）
        verify(imageGenService, org.mockito.Mockito.times(3)).generate(anyString(), anyString(), any(TaskRequest.class));
        verify(repository, atLeastOnce()).failTask(eq("t1"), anyString(), anyString(), anyString());
    }

    @Test
    void failTaskWhenNoAccountCandidate() throws Exception {
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", TaskRepository.STATUS_QUEUED, 1)));
        when(scheduler.select(any(), any())).thenThrow(new HttpErrorException(503, "NO_AVAILABLE_ACCOUNT", "该模型暂无可用账号，请联系管理员"));

        queueService.registerRuntimeState("t1", MODEL_ID.toString(), List.of(),
                new TaskQueueService.Source("1.2.3.4", USER_ID.toString()));
        queueService.enqueue("t1");
        awaitTerminal("t1");

        verify(imageGenService, never()).generate(anyString(), anyString(), any(TaskRequest.class));
        verify(repository, atLeastOnce()).failTask(eq("t1"), anyString(), anyString(), anyString());
    }

    // ===== per-user pending counters (T12, ADR-27) =====

    @Test
    void pendingCountTracksPerUser() {
        queueService.registerRuntimeState("t1", MODEL_ID.toString(), List.of(),
                new TaskQueueService.Source("1.2.3.4", USER_ID.toString()));
        assertThat(queueService.getPendingCountByUser(USER_ID.toString())).isEqualTo(1);
        queueService.cleanupTaskRuntimeState("t1");
        assertThat(queueService.getPendingCountByUser(USER_ID.toString())).isZero();
    }

    private void awaitTerminal(String taskId) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        for (int i = 0; i < 50; i++) {
            try {
                verify(repository, org.mockito.Mockito.atLeastOnce())
                        .completeTask(eq(taskId), anyString(), any(), anyString(), anyString());
                return;
            } catch (AssertionError ignored) {
                // not terminal yet
            }
            try {
                verify(repository, org.mockito.Mockito.atLeastOnce())
                        .failTask(eq(taskId), anyString(), anyString(), anyString());
                return;
            } catch (AssertionError ignored) {
                // not terminal yet
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task did not reach terminal state: " + taskId);
    }
}
