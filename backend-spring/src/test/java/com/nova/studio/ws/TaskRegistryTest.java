package com.nova.studio.ws;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TaskRegistry} subscription semantics (ports of the Node
 * backend's {@code handleSubscribeTasks} behavior) using serialized task
 * message maps.
 */
class TaskRegistryTest {

    private static Map<String, Object> task(String id, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("status", status);
        return map;
    }

    @Test
    void subscribePushesCurrentTaskStateAndTerminalAutoUnsubscribes() {
        TaskRegistry registry = new TaskRegistry();
        registry.put("t1", task("t1", TaskRegistry.STATUS_COMPLETED));

        List<Map<String, Object>> pushed = registry.subscribe("s1", List.of("t1"), 200, 500);

        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).get("status")).isEqualTo(TaskRegistry.STATUS_COMPLETED);
        // terminal task must be auto-unsubscribed so later broadcasts don't target it
        assertThat(registry.broadcast(task("t1", TaskRegistry.STATUS_PROCESSING))).isEmpty();
    }

    @Test
    void subscribeUnknownIdPushesExpiredFallback() {
        TaskRegistry registry = new TaskRegistry();
        List<Map<String, Object>> pushed = registry.subscribe("s1", List.of("missing"), 200, 500);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).get("status")).isEqualTo(TaskRegistry.STATUS_EXPIRED);
        assertThat(pushed.get(0).get("error")).isEqualTo(TaskRegistry.EXPIRED_ERROR);
    }

    @Test
    void broadcastReachesOnlySubscribedSessionsAndUnsubscribesTerminal() {
        TaskRegistry registry = new TaskRegistry();
        registry.put("t1", task("t1", TaskRegistry.STATUS_QUEUED));
        registry.put("t2", task("t2", TaskRegistry.STATUS_QUEUED));
        registry.subscribe("s1", List.of("t1"), 200, 500);
        registry.subscribe("s2", List.of("t2"), 200, 500);

        assertThat(registry.broadcast(task("t1", TaskRegistry.STATUS_COMPLETED))).containsExactly("s1");
        // t1 terminal now → subsequent broadcast should not re-target s1
        assertThat(registry.broadcast(task("t1", TaskRegistry.STATUS_PROCESSING))).isEmpty();
    }

    @Test
    void subscribeEnforcesMaxIdsPerMessageAndMaxPerSocket() {
        TaskRegistry registry = new TaskRegistry();
        List<String> ids = java.util.stream.IntStream.rangeClosed(1, 10).mapToObj(i -> "t" + i).toList();

        List<Map<String, Object>> pushed = registry.subscribe("s1", ids, 3, 2);

        assertThat(pushed).hasSize(3); // maxIdsPerMessage=3 wins first
        assertThat(registry.broadcast(task("t4", TaskRegistry.STATUS_PROCESSING))).isEmpty();
    }

    @Test
    void unsubscribeRemovesTaskIds() {
        TaskRegistry registry = new TaskRegistry();
        registry.subscribe("s1", List.of("t1"), 200, 500);
        registry.unsubscribe("s1", List.of("t1"));
        assertThat(registry.broadcast(task("t1", TaskRegistry.STATUS_PROCESSING))).isEmpty();
    }

    @Test
    void skipsBlankAndNullIds() {
        TaskRegistry registry = new TaskRegistry();
        List<Map<String, Object>> pushed = registry.subscribe(
                "s1", java.util.Arrays.asList("ok", null, ""), 200, 500);
        assertThat(pushed).hasSize(1);
        assertThat(pushed.get(0).get("id")).isEqualTo("ok");
    }

    @Test
    void lookupBackedSubscribeResolvesFromLookupFirst() {
        TaskRegistry registry = new TaskRegistry(id -> {
            if ("db-task".equals(id)) {
                return task("db-task", TaskRegistry.STATUS_PROCESSING);
            }
            return null;
        });
        registry.put("mem-task", task("mem-task", TaskRegistry.STATUS_QUEUED));

        List<Map<String, Object>> pushed = registry.subscribe(
                "s1", List.of("db-task", "mem-task", "missing"), 200, 500);
        assertThat(pushed).hasSize(3);
        assertThat(pushed.get(0).get("status")).isEqualTo(TaskRegistry.STATUS_PROCESSING);
        assertThat(pushed.get(1).get("status")).isEqualTo(TaskRegistry.STATUS_QUEUED);
        assertThat(pushed.get(2).get("status")).isEqualTo(TaskRegistry.STATUS_EXPIRED);
    }

    @Test
    void queueSubscriptionsTrackedPerSession() {
        TaskRegistry registry = new TaskRegistry();
        registry.subscribeQueue("s1");
        registry.subscribeQueue("s2");
        assertThat(registry.queueSubscribers()).containsExactlyInAnyOrder("s1", "s2");
        registry.unsubscribeQueue("s1");
        assertThat(registry.queueSubscribers()).containsExactly("s2");
    }
}
