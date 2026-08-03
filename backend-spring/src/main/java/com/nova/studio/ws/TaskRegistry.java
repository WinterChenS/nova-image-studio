package com.nova.studio.ws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket subscription bookkeeping (T1.7) — port of the Node backend's
 * {@code taskSubscriptions} / {@code queueSubscribers} maps. Messages are
 * opaque {@code Map}s (the serialized {@code NovaTaskResponse} shape) so the
 * registry stays decoupled from the persistence layer: production looks tasks
 * up via the injected {@link WsTaskLookup}, while the in-memory {@code put}
 * path exists for tests and the spike controller.
 *
 * <p>Semantics (ARCH §E.3, 1:1 with Node): ≤ {@code maxIdsPerMessage} ids per
 * subscribeTasks message, ≤ {@code maxSubscriptionsPerSocket} ids per socket,
 * immediate push of the current task state, terminal tasks
 * (completed/failed/expired) auto-unsubscribed after the push, unknown ids →
 * expired fallback.
 */
public class TaskRegistry {

    public static final String STATUS_QUEUED = "排队中";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";

    /** Hardcoded expired payload — Node broadcastTaskExpired/serializeTask fallback. */
    public static final String EXPIRED_ERROR = "该任务已超出取回时间";

    private final WsTaskLookup lookup;
    private final Map<String, Map<String, Object>> inMemoryTasks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();
    private final Set<String> queueSubscribers = ConcurrentHashMap.newKeySet();

    public TaskRegistry() {
        this(null);
    }

    public TaskRegistry(WsTaskLookup lookup) {
        this.lookup = lookup;
    }

    // ===== in-memory store (tests / spike controller) =====

    public void put(String id, Map<String, Object> task) {
        inMemoryTasks.put(id, task);
    }

    public Map<String, Object> get(String id) {
        return inMemoryTasks.get(id);
    }

    // ===== subscriptions =====

    /**
     * Subscribes a socket to task ids honoring both limits; pushes the current
     * state of each id (lookup → in-memory → expired fallback). Terminal states
     * are auto-unsubscribed.
     */
    public List<Map<String, Object>> subscribe(String sessionId, List<String> taskIds,
                                               int maxIdsPerMessage, int maxPerSocket) {
        if (taskIds == null) {
            return List.of();
        }
        Set<String> set = subscriptions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        List<Map<String, Object>> pushed = new ArrayList<>();
        int added = 0;
        for (String id : taskIds) {
            if (added >= maxIdsPerMessage) {
                break;
            }
            if (id == null || id.isBlank()) {
                continue;
            }
            if (!set.contains(id) && set.size() >= maxPerSocket) {
                break;
            }
            if (set.add(id)) {
                added++;
            }
            Map<String, Object> task = resolveTask(id);
            pushed.add(task);
            if (isTerminal(statusOf(task))) {
                set.remove(id);
            }
        }
        return pushed;
    }

    public void unsubscribe(String sessionId, List<String> taskIds) {
        Set<String> set = subscriptions.get(sessionId);
        if (set == null || taskIds == null) {
            return;
        }
        for (String id : taskIds) {
            set.remove(id);
        }
    }

    /**
     * Broadcasts a task update to every socket subscribed to that id; terminal
     * tasks auto-unsubscribe. Returns the target session ids.
     */
    public List<String> broadcast(Map<String, Object> task) {
        if (task == null) {
            return List.of();
        }
        String id = String.valueOf(task.get("id"));
        inMemoryTasks.put(id, task);
        List<String> targets = new ArrayList<>();
        subscriptions.forEach((sessionId, ids) -> {
            if (ids.contains(id)) {
                targets.add(sessionId);
                if (isTerminal(statusOf(task))) {
                    ids.remove(id);
                }
            }
        });
        return targets;
    }

    // ===== queue subscriptions =====

    public Set<String> queueSubscribers() {
        return queueSubscribers;
    }

    public void subscribeQueue(String sessionId) {
        queueSubscribers.add(sessionId);
    }

    public void unsubscribeQueue(String sessionId) {
        queueSubscribers.remove(sessionId);
    }

    // ===== helpers =====

    private Map<String, Object> resolveTask(String id) {
        if (lookup != null) {
            Map<String, Object> task = lookup.loadTaskMessage(id);
            if (task != null) {
                return task;
            }
        }
        Map<String, Object> task = inMemoryTasks.get(id);
        if (task != null) {
            return task;
        }
        Map<String, Object> expired = new LinkedHashMap<>();
        expired.put("id", id);
        expired.put("status", STATUS_EXPIRED);
        expired.put("error", EXPIRED_ERROR);
        return expired;
    }

    private static String statusOf(Map<String, Object> task) {
        Object status = task == null ? null : task.get("status");
        return status == null ? null : String.valueOf(status);
    }

    public static boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_EXPIRED.equals(status);
    }
}
