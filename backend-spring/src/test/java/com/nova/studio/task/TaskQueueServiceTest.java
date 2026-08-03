package com.nova.studio.task;

import com.nova.studio.imagegen.ImageGenService;
import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.ws.TaskEventBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 * T1.2 — queue slot concurrency / oversized exclusive run / state machine
 * (acceptance: 单元测试覆盖队列并发/独占/状态流转). Mirrors the Node
 * {@code drainQueue} semantics: capacity is counted in image slots
 * ({@code parallelCount}), an oversized task may run alone only when the queue
 * is idle, and tasks flow 排队中 → processing → completed/failed with
 * per-item generation.
 */
class TaskQueueServiceTest {

    private TaskRepository repository;
    private ImageGenService imageGenService;
    private ImageStorageService imageStorageService;
    private TaskEventBroadcaster broadcaster;
    private QueueStatsService queueStatsService;
    private TaskQueueService queueService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskRepository.TaskRow row(String id, String status, int parallelCount) {
        return new TaskRepository.TaskRow(id, null, status, "text-to-image",
                "{\"mode\":\"text-to-image\",\"protocol\":\"openai\",\"baseUrl\":\"http://upstream\","
                        + "\"prompt\":\"a cat\",\"model\":\"gpt-image-1\",\"parallelCount\":" + parallelCount
                        + ",\"images\":[]}",
                null, null, null, Instant.parse("2026-08-03T00:00:00Z"), null, null);
    }

    @BeforeEach
    void setUp() {
        repository = mock(TaskRepository.class);
        imageGenService = mock(ImageGenService.class);
        imageStorageService = mock(ImageStorageService.class);
        broadcaster = mock(TaskEventBroadcaster.class);
        queueStatsService = mock(QueueStatsService.class);
        queueService = new TaskQueueService(repository, imageGenService, imageStorageService, broadcaster,
                MAPPER, queueStatsService, 43_200_000, 1_800_000);
    }

    @Test
    void tasksExceedingSlotCapacityStayQueuedUntilSlotsFree() throws Exception {
        when(queueStatsService.getMaxServerConcurrency()).thenReturn(2);
        when(repository.findById("t1")).thenReturn(Optional.of(row("t1", TaskRepository.STATUS_QUEUED, 2)));
        when(repository.findById("t2")).thenReturn(Optional.of(row("t2", TaskRepository.STATUS_QUEUED, 2)));
        when(imageStorageService.saveImageToDisk(anyString(), anyInt(), anyInt(), any(byte[].class), anyString()))
                .thenReturn("/api/nova/images/x/0");

        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class)))
                .thenAnswer(inv -> {
                    firstStarted.countDown();
                    release.await(5, TimeUnit.SECONDS);
                    return "QUJD";
                });

        queueService.registerRuntimeState("t1", "key", List.of(), new TaskQueueService.Source("1.2.3.4", "hash"));
        queueService.registerRuntimeState("t2", "key", List.of(), new TaskQueueService.Source("1.2.3.4", "hash"));
        queueService.enqueue("t1");
        queueService.enqueue("t2");

        assertThat(firstStarted.await(5, TimeUnit.SECONDS)).as("first task started").isTrue();
        // t2 must NOT have started while t1 holds 2/2 slots
        Thread.sleep(200);
        verify(repository, atLeastOnce()).updateStatus(eq("t1"), eq(TaskRepository.STATUS_PROCESSING));
        verify(repository, never()).updateStatus(eq("t2"), eq(TaskRepository.STATUS_PROCESSING));

        release.countDown();
        awaitFinalState("t2");
        verify(repository, atLeastOnce()).updateStatus(eq("t2"), eq(TaskRepository.STATUS_PROCESSING));
        verify(repository, atLeastOnce()).completeTask(eq("t2"), anyString(), any(), anyString(), anyString());
    }

    @Test
    void oversizedTaskRunsAloneWhenQueueIdle() throws Exception {
        // cap = 2 slots, task needs 4 → only schedulable when idle (Node oversized exception)
        when(queueStatsService.getMaxServerConcurrency()).thenReturn(2);
        when(repository.findById("big")).thenReturn(Optional.of(row("big", TaskRepository.STATUS_QUEUED, 4)));
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class))).thenReturn("QUJD");
        when(imageStorageService.saveImageToDisk(anyString(), anyInt(), anyInt(), any(byte[].class), anyString()))
                .thenReturn("/api/nova/images/big/0");

        queueService.registerRuntimeState("big", "key", List.of(), new TaskQueueService.Source("1.2.3.4", "hash"));
        queueService.enqueue("big");

        awaitFinalState("big");
        verify(repository, atLeastOnce()).updateStatus(eq("big"), eq(TaskRepository.STATUS_PROCESSING));
        verify(repository, atLeastOnce()).completeTask(eq("big"), anyString(), any(), anyString(), anyString());
    }

    @Test
    void stateMachineCompletesWithImagesResult() throws Exception {
        when(queueStatsService.getMaxServerConcurrency()).thenReturn(50);
        when(repository.findById("ok")).thenReturn(Optional.of(row("ok", TaskRepository.STATUS_QUEUED, 1)));
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class))).thenReturn("QUJD");
        when(imageStorageService.saveImageToDisk(eq("ok"), eq(0), eq(0), any(byte[].class), anyString()))
                .thenReturn("/api/nova/images/ok/0");

        queueService.registerRuntimeState("ok", "key", List.of(), new TaskQueueService.Source("1.2.3.4", "hash"));
        queueService.enqueue("ok");

        awaitFinalState("ok");
        verify(repository).updateStatus(eq("ok"), eq(TaskRepository.STATUS_PROCESSING));
        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(repository).completeTask(eq("ok"), resultJson.capture(), org.mockito.ArgumentMatchers.isNull(), anyString(), anyString());
        assertThat(resultJson.getValue()).contains("URL:/api/nova/images/ok/0");
        verify(repository).updateItemImageData(eq("ok"), eq(0), eq(TaskRepository.STATUS_COMPLETED),
                anyString(), anyString());
    }

    @Test
    void failedGenerationMarksTaskFailed() throws Exception {
        when(queueStatsService.getMaxServerConcurrency()).thenReturn(50);
        when(repository.findById("bad")).thenReturn(Optional.of(row("bad", TaskRepository.STATUS_QUEUED, 1)));
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class)))
                .thenThrow(new IllegalStateException("upstream exploded"));

        queueService.registerRuntimeState("bad", "key", List.of(), new TaskQueueService.Source("1.2.3.4", "hash"));
        queueService.enqueue("bad");

        awaitFinalState("bad");
        verify(repository).failTask(eq("bad"), org.mockito.ArgumentMatchers.startsWith("所有图片生成失败:"),
                anyString(), anyString());
        verify(repository).updateItemError(eq("bad"), eq(0), eq(TaskRepository.STATUS_FAILED),
                anyString(), anyString());
    }

    @Test
    void multiUrlExpansionSavesEachSubImage() throws Exception {
        when(queueStatsService.getMaxServerConcurrency()).thenReturn(50);
        when(repository.findById("multi")).thenReturn(Optional.of(row("multi", TaskRepository.STATUS_QUEUED, 1)));
        when(imageGenService.generate(anyString(), anyString(), any(TaskRequest.class)))
                .thenReturn("MULTI_URL:http://a/x.png|||http://b/y.png");
        when(imageStorageService.downloadUrlToDisk(eq("multi"), eq(0), anyInt(), anyString()))
                .thenReturn("/api/nova/images/multi/0");

        queueService.registerRuntimeState("multi", "key", List.of(), new TaskQueueService.Source("1.2.3.4", "hash"));
        queueService.enqueue("multi");

        awaitFinalState("multi");
        verify(imageStorageService).downloadUrlToDisk(eq("multi"), eq(0), eq(0), eq("http://a/x.png"));
        verify(imageStorageService).downloadUrlToDisk(eq("multi"), eq(0), eq(1), eq("http://b/y.png"));
        ArgumentCaptor<String> resultJson = ArgumentCaptor.forClass(String.class);
        verify(repository).completeTask(eq("multi"), resultJson.capture(), any(), anyString(), anyString());
        assertThat(resultJson.getValue()).contains("\"images\":[\"URL:/api/nova/images/multi/0\",\"URL:/api/nova/images/multi/0\"]");
    }

    /** Waits (polling the mock) until the task reached its terminal DB write. */
    private void awaitFinalState(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                verify(repository).completeTask(eq(taskId), anyString(), any(), anyString(), anyString());
                return;
            } catch (org.mockito.exceptions.verification.WantedButNotInvoked ignored) {
                // not completed yet
            }
            try {
                verify(repository).failTask(eq(taskId), anyString(), anyString(), anyString());
                return;
            } catch (org.mockito.exceptions.verification.WantedButNotInvoked ignored) {
                // not failed yet
            }
            Thread.sleep(50);
        }
        throw new AssertionError("task " + taskId + " did not reach a terminal state within 10s");
    }

    private static String isNullValue() {
        return null;
    }
}