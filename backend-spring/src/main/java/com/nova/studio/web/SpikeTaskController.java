package com.nova.studio.web;

import com.nova.studio.ws.NovaWebSocketHandler;
import com.nova.studio.ws.TaskRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Spike-scoped task controller: demonstrates the WS subscribe/broadcast path
 * end-to-end (REST → TaskRegistry → WS broadcast). Replaced by the real task
 * API in M1 (POST /api/nova/tasks).
 */
@RestController
@RequestMapping("/api/nova/spike/tasks")
public class SpikeTaskController {

    private final TaskRegistry taskRegistry;
    private final NovaWebSocketHandler wsHandler;

    public SpikeTaskController(TaskRegistry taskRegistry, NovaWebSocketHandler wsHandler) {
        this.taskRegistry = taskRegistry;
        this.wsHandler = wsHandler;
    }

    /** Creates or updates a task and broadcasts the update over WS. */
    @PostMapping
    public Map<String, Object> upsert(@RequestBody TaskRegistry.Task task) {
        taskRegistry.put(task);
        wsHandler.broadcastTask(task);
        return Map.of("taskId", task.id(), "status", task.status(), "broadcast", true);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        TaskRegistry.Task task = taskRegistry.get(id);
        if (task == null) {
            return Map.of("id", id, "status", TaskRegistry.STATUS_EXPIRED, "error", "该任务已超出取回时间");
        }
        return task.toMessage();
    }
}
