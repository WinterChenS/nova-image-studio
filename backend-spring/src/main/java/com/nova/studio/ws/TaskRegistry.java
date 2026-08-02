package com.nova.studio.ws;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory task registry + subscription bookkeeping for the M0 spike.
 *
 * <p>Replicates the subset of the Node backend's WS semantics needed by the
 * frontend {@code ccode-task-socket.ts} client: subscribeTasks immediate push,
 * terminal auto-unsubscribe, per-socket subscription limits. The real persistent
 * store (PostgreSQL tasks/task_items) lands in M1; this registry only backs the
 * WS protocol spike.
 */
public class TaskRegistry {

    /** Serialized task shape sent to the frontend (field-compatible subset). */
    public record Task(String id, String status, String error, String resultJson, String createdAt) {
        public Map<String, Object> toMessage() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("status", status);
            map.put("error", error);
            map.put("resultJson", resultJson);
            map.put("createdAt", createdAt);
            return map;
        }
    }

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_EXPIRED = "expired";

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscriptions = new ConcurrentHashMap<>();

    public void put(Task task) {
        tasks.put(task.id(), task);
    }

    public Task get(String id) {
        return tasks.get(id);
    }

    public void update(Task task) {
        tasks.put(task.id(), task);
    }

    /** Subscribes a socket (identified by sessionId) to task ids, honoring limits. */
    public List<Task> subscribe(String sessionId, List<String> taskIds, int maxIdsPerMessage, int maxPerSocket) {
        Set<String> set = subscriptions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet());
        List<Task> pushed = new ArrayList<>();
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
            Task task = tasks.get(id);
            if (task == null) {
                task = new Task(id, STATUS_EXPIRED, "该任务已超出取回时间", null, null);
            }
            pushed.add(task);
            if (isTerminal(task.status())) {
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

    /** Broadcasts a task update to every socket subscribed to that task id. */
    public List<String> broadcast(Task task) {
        tasks.put(task.id(), task);
        List<String> targets = new ArrayList<>();
        subscriptions.forEach((sessionId, ids) -> {
            if (ids.contains(task.id())) {
                targets.add(sessionId);
                if (isTerminal(task.status())) {
                    ids.remove(task.id());
                }
            }
        });
        return targets;
    }

    public Set<String> subscribedSessions() {
        return subscriptions.keySet();
    }

    public static boolean isTerminal(String status) {
        return STATUS_COMPLETED.equals(status) || STATUS_FAILED.equals(status) || STATUS_EXPIRED.equals(status);
    }

    /** Serializes a message to JSON; returns null when serialization fails. */
    public static String toJson(ObjectMapper mapper, Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Parses a client message; returns null for invalid JSON. */
    public static JsonNode parse(ObjectMapper mapper, String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
