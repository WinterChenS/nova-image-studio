package com.nova.studio.web;

import com.nova.studio.auth.AuthUser;
import com.nova.studio.project.ProjectService;
import com.nova.studio.task.TaskLookupService;
import com.nova.studio.task.TaskRepository;
import com.nova.studio.task.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task API (T1.1/T1.2 + M2 T2.2/T2.4 + WIN-22 D.3):
 * <ul>
 *   <li>{@code POST /api/nova/tasks} → 202 {@code {taskId}} — requires login
 *       (Q1), resolves the model config server-side, carries optional
 *       {@code projectId} (F-4, ADR-17 default fallback);</li>
 *   <li>{@code GET /api/nova/tasks} — WIN-22: owner-scoped history list with
 *       projectId/status filters + pagination (Q1 — the generation-history
 *       filter carrier);</li>
 *   <li>{@code GET /api/nova/tasks/:id} → task object (owner-only for
 *       user-owned tasks; NULL-owner legacy tasks readable by any logged-in
 *       user — D2 登录收口后匿名不再可达);</li>
 *   <li>{@code POST /api/nova/tasks/:id/ack} → TTL renewal (2min grace);</li>
 *   <li>{@code PATCH /api/nova/tasks/:id/project} — WIN-22 (F-5): one-click
 *       assign to a project (owner-only; NULL-owner legacy rows stay 未分类).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/nova/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskLookupService taskLookupService;
    private final TaskRepository taskRepository;
    private final ProjectService projectService;

    public TaskController(TaskService taskService,
                          TaskLookupService taskLookupService,
                          TaskRepository taskRepository,
                          ProjectService projectService) {
        this.taskService = taskService;
        this.taskLookupService = taskLookupService;
        this.taskRepository = taskRepository;
        this.projectService = projectService;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String projectId,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size,
                                    @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) {
            throw new com.nova.studio.infra.HttpErrorException(401, "UNAUTHORIZED", "请先登录");
        }
        int safeSize = Math.min(Math.max(size <= 0 ? 20 : size, 1), 100);
        int safePage = Math.max(page <= 0 ? 1 : page, 1);
        TaskRepository.TaskPage result = taskRepository.searchByUser(authUser.id(), projectId, status, safePage, safeSize);
        List<Map<String, Object>> items = new ArrayList<>();
        for (TaskRepository.TaskRow row : result.items()) {
            items.add(taskLookupService.serializeTask(row));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("total", result.total());
        body.put("page", safePage);
        body.put("size", safeSize);
        return body;
    }

    @PatchMapping("/{taskId}/project")
    public Map<String, Object> assignProject(@PathVariable String taskId, @RequestBody JsonNode body,
                                             @AuthenticationPrincipal AuthUser authUser) {
        if (authUser == null) {
            throw new com.nova.studio.infra.HttpErrorException(401, "UNAUTHORIZED", "请先登录");
        }
        TaskRepository.TaskRow row = taskRepository.findById(taskId)
                .orElseThrow(() -> new com.nova.studio.infra.HttpErrorException(404, "NOT_FOUND", "任务不存在"));
        if (row.userId() == null || row.userId().isBlank()) {
            throw new com.nova.studio.infra.HttpErrorException(400, "LEGACY_TASK", "历史遗留任务不可归入项目");
        }
        if (!row.userId().equals(authUser.id().toString())) {
            throw new com.nova.studio.infra.HttpErrorException(404, "NOT_FOUND", "任务不存在");
        }
        String projectId = body.hasNonNull("projectId") ? body.get("projectId").asText() : null;
        String resolved;
        if (projectId == null || projectId.isBlank() || "__unclassified__".equals(projectId)) {
            resolved = null;
        } else {
            if (!projectService.owns(authUser.id(), projectId)) {
                throw new com.nova.studio.infra.HttpErrorException(404, "NOT_FOUND", "项目不存在");
            }
            resolved = projectId;
        }
        taskRepository.updateProjectId(taskId, resolved);
        return Map.of("ok", true);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody JsonNode body, @AuthenticationPrincipal AuthUser authUser,
                                                      HttpServletRequest request) {
        // The chain requires auth for POST /api/nova/tasks; TaskService keeps its own
        // 401 guard for anonymous (null) principals (Q1).
        String taskId = taskService.createTask(body, clientIp(request), authUser);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("taskId", taskId));
    }

    @GetMapping("/{taskId}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String taskId, @AuthenticationPrincipal AuthUser authUser) {
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
