package com.nova.studio.task;

import com.nova.studio.ws.WsTaskLookup;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DB-backed WS task lookup (T1.7) — serializes a task row into the frontend
 * {@code NovaTaskResponse} shape (Node {@code serializeTask}), deriving
 * {@code expired} when {@code expires_at} has passed. Kept as a small leaf
 * service (repository + mapper only) so the WS handler can push task states
 * without forming a bean cycle with the task pipeline.
 */
@Service
public class TaskLookupService implements WsTaskLookup {

    private final TaskRepository repository;
    private final ObjectMapper objectMapper;

    public TaskLookupService(TaskRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> serializeTask(TaskRepository.TaskRow task) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (task.expiresAt() != null && !task.expiresAt().isAfter(Instant.now())) {
            map.put("id", task.id());
            map.put("status", TaskRepository.STATUS_EXPIRED);
            map.put("error", "该任务已超出取回时间");
            return map;
        }
        map.put("id", task.id());
        map.put("status", task.status());
        if (task.mode() != null) {
            map.put("mode", task.mode());
        }
        if (task.resultJson() != null) {
            map.put("result", parseJsonOrRaw(task.resultJson()));
        }
        if (task.error() != null) {
            map.put("error", task.error());
        }
        if (task.warning() != null) {
            map.put("warning", task.warning());
        }
        if (task.createdAt() != null) {
            map.put("createdAt", task.createdAt().toString());
        }
        if (task.completedAt() != null) {
            map.put("completedAt", task.completedAt().toString());
        }
        if (task.expiresAt() != null) {
            map.put("expiresAt", task.expiresAt().toString());
        }
        return map;
    }

    private Object parseJsonOrRaw(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    @Override
    public Map<String, Object> loadTaskMessage(String taskId) {
        return repository.findById(taskId).map(this::serializeTask).orElse(null);
    }
}
