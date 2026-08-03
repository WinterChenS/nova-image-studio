package com.nova.studio.web;

import com.nova.studio.ws.NovaWebSocketHandler;
import com.nova.studio.ws.TaskRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Spike-scoped task controller: demonstrates the WS subscribe/broadcast path
 * end-to-end (REST → TaskRegistry → WS broadcast) with an in-memory task map.
 * Used by the WS e2e tests; the production task API lives in
 * {@link TaskController}.
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
    public Map<String, Object> upsert(@RequestBody Map<String, Object> task) {
        String id = String.valueOf(task.getOrDefault("id", "unknown"));
        taskRegistry.put(id, task);
        wsHandler.broadcastTaskMessage(task);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", id);
        result.put("status", task.get("status"));
        result.put("broadcast", true);
        return result;
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable String id) {
        Map<String, Object> task = taskRegistry.get(id);
        if (task == null) {
            Map<String, Object> expired = new LinkedHashMap<>();
            expired.put("id", id);
            expired.put("status", TaskRegistry.STATUS_EXPIRED);
            expired.put("error", TaskRegistry.EXPIRED_ERROR);
            return expired;
        }
        return task;
    }
}
