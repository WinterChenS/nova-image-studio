package com.nova.studio.web;

import com.nova.studio.auth.AuthFilter;
import com.nova.studio.auth.AuthUser;
import com.nova.studio.task.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Task API (T1.1/T1.2 + M2 T2.2/T2.4) — contract-compatible with the Node backend and the
 * frontend {@code ccode-task-client.ts}:
 * <ul>
 *   <li>{@code POST /api/nova/tasks} → 202 {@code {taskId}} — requires login
 *       (Q1) and resolves the model config server-side from {@code modelId};</li>
 *   <li>{@code GET /api/nova/tasks/:id} → task object (owner-only for
 *       user-owned tasks, NULL-owner legacy tasks stay anonymously readable);</li>
 *   <li>{@code POST /api/nova/tasks/:id/ack} → TTL renewal (2min grace).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/nova/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body, HttpServletRequest request) {
        AuthUser authUser = AuthFilter.current(request);
        String taskId = taskService.createTask(body, clientIp(request), authUser);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("taskId", taskId));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String taskId, HttpServletRequest request) {
        AuthUser authUser = AuthFilter.current(request);
        Map<String, Object> task = taskService.getSerializedTask(taskId, authUser);
        if (task == null) {
            Map<String, Object> expired = new LinkedHashMap<>();
            expired.put("id", taskId);
            expired.put("status", "expired");
            expired.put("error", "该任务已超出取回时间");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(expired);
        }
        return ResponseEntity.ok(task);
    }

    @PostMapping("/{taskId}/ack")
    public Map<String, Object> ack(@PathVariable String taskId) {
        taskService.ackTask(taskId);
        return Map.of("ok", true);
    }

    /** Node getClientIp: first X-Forwarded-For, else remote address, minus ::ffff: prefix. */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded != null ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
        return ip == null ? "unknown" : ip.replaceFirst("^::ffff:", "");
    }
}
