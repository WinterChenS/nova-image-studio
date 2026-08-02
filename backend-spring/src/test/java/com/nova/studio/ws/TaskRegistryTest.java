package com.nova.studio.ws;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TaskRegistry} subscription semantics (ports of the Node
 * backend's {@code handleSubscribeTasks} behavior).
 */
class TaskRegistryTest {

    @Test
    void subscribePushesCurrentTaskStateAndTerminalAutoUnsubscribes() {
        TaskRegistry registry = new TaskRegistry();
        registry.put(new TaskRegistry.Task("t1", TaskRegistry.STATUS_COMPLETED, null, "{}", "2026-08-02"));

        List<TaskRegistry.Task> pushed = registry.subscribe("s1", List.of("t1"), 200, 500);

        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).status()).isEqualTo(TaskRegistry.STATUS_COMPLETED);
        // terminal task must be auto-unsubscribed so later broadcasts don't target it
        assertThat(registry.broadcast(new TaskRegistry.Task("t1", TaskRegistry.STATUS_PROCESSING, null, null, null)))
                .isEmpty();
    }

    @Test
    void subscribeUnknownIdPushesExpiredFallback() {
        TaskRegistry registry = new TaskRegistry();
        List<TaskRegistry.Task> pushed = registry.subscribe("s1", List.of("missing"), 200, 500);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).status()).isEqualTo(TaskRegistry.STATUS_EXPIRED);
        assertThat(pushed.get(0).error()).isEqualTo("该任务已超出取回时间");
    }

    @Test
    void broadcastReachesOnlySubscribedSessionsAndUnsubscribesTerminal() {
        TaskRegistry registry = new TaskRegistry();
        // Pre-register non-terminal tasks so subscribe keeps them active
        registry.put(new TaskRegistry.Task("t1", TaskRegistry.STATUS_QUEUED, null, null, null));
        registry.put(new TaskRegistry.Task("t2", TaskRegistry.STATUS_QUEUED, null, null, null));
        registry.subscribe("s1", List.of("t1"), 200, 500);
        registry.subscribe("s2", List.of("t2"), 200, 500);

        assertThat(registry.broadcast(new TaskRegistry.Task("t1", TaskRegistry.STATUS_COMPLETED, null, null, null)))
                .containsExactly("s1");
        // t1 terminal now → subsequent broadcast should not re-target s1
        assertThat(registry.broadcast(new TaskRegistry.Task("t1", TaskRegistry.STATUS_PROCESSING, null, null, null)))
                .isEmpty();
    }

    @Test
    void subscribeEnforcesMaxIdsPerMessageAndMaxPerSocket() {
        TaskRegistry registry = new TaskRegistry();
        List<String> ids = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(i -> "t" + i).toList();

        List<TaskRegistry.Task> pushed = registry.subscribe("s1", ids, 3, 2);

        assertThat(pushed).hasSize(3); // maxIdsPerMessage=3 wins first
        assertThat(registry.broadcast(new TaskRegistry.Task("t4", TaskRegistry.STATUS_PROCESSING, null, null, null)))
                .isEmpty(); // t4 not subscribed (cut off by per-message limit)
    }

    @Test
    void unsubscribeRemovesTaskIds() {
        TaskRegistry registry = new TaskRegistry();
        registry.subscribe("s1", List.of("t1"), 200, 500);
        registry.unsubscribe("s1", List.of("t1"));
        assertThat(registry.broadcast(new TaskRegistry.Task("t1", TaskRegistry.STATUS_PROCESSING, null, null, null)))
                .isEmpty();
    }

    @Test
    void skipsBlankAndNullIds() {
        TaskRegistry registry = new TaskRegistry();
        List<TaskRegistry.Task> pushed = registry.subscribe("s1", java.util.Arrays.asList("ok", null, ""), 200, 500);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).id()).isEqualTo("ok");
    }
}
