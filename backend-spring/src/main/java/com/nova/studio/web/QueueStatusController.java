package com.nova.studio.web;

import com.nova.studio.task.QueueStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Queue status (T1.6) — {@code GET /api/nova/queue-status}, field-compatible
 * with the frontend {@code NovaQueueStatus} ({@code ccode-task-client.ts}).
 */
@RestController
@RequestMapping("/api/nova/queue-status")
public class QueueStatusController {

    private final QueueStatsService queueStatsService;

    public QueueStatusController(QueueStatsService queueStatsService) {
        this.queueStatsService = queueStatsService;
    }

    @GetMapping
    public Map<String, Object> status() {
        return queueStatsService.getQueueStatus();
    }
}
