package com.nova.studio.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * TTL sweep (T1.1) — port of the Node backend's
 * {@code setInterval(cleanupExpiredTasks, 5 * 60 * 1000)}: every 5 minutes
 * expired tasks (expires_at in the past, TTL 12h, renewed by ack) are
 * broadcast as expired over WS, deleted from PostgreSQL and their image files
 * removed.
 */
@Component
public class TaskCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskCleanupScheduler.class);

    private final TaskService taskService;

    public TaskCleanupScheduler(TaskService taskService) {
        this.taskService = taskService;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupExpiredTasks() {
        try {
            taskService.cleanupExpiredTasks();
        } catch (Exception e) {
            log.error("[cleanup] 过期任务清理失败", e);
        }
    }
}
