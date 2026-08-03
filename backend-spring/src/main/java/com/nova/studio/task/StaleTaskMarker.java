package com.nova.studio.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Startup stale-task marking (T1.9) — port of the Node backend's
 * {@code initDatabase}: legacy 'queued' rows are normalized to '排队中' and any
 * queued/processing tasks from a previous run are failed with
 * "服务器重启，任务已中断，请重新生成" and their image files removed.
 *
 * <p>Runs as a {@link SmartLifecycle} with a phase just below the web server
 * start so no request can be accepted before stale marking completes (the Node
 * backend does the same before listening).
 */
@Component
public class StaleTaskMarker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(StaleTaskMarker.class);

    private final TaskService taskService;
    private volatile boolean running;

    public StaleTaskMarker(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public void start() {
        try {
            var interrupted = taskService.markInterruptedTasksFailed();
            log.info("[startup] 残留任务标记失败并清理产物: {} 个任务", interrupted.size());
        } catch (Exception e) {
            log.error("[startup] 残留任务标记失败", e);
        }
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Runs before the web server starts (WebServerStartStopLifecycle phase = MAX_VALUE). */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
