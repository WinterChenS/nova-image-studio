package com.nova.studio.task;

import com.nova.studio.ws.NovaWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Graceful shutdown (T1.9) — port of the Node backend's
 * {@code registerShutdownHandlers}: stop accepting new tasks (queue stats flip
 * {@code acceptingNewTasks:false}), close the WS server, then wait for in-flight
 * tasks to finish (bounded wait) before the context stops. The Node backend
 * waits for running task promises with no hard cap beyond the task timeout;
 * we bound the wait to the same 30min request timeout.
 */
@Component
public class ShutdownCoordinator implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShutdownCoordinator.class);

    private final TaskQueueService queueService;
    private final ShutdownFlag shutdownFlag;
    private final NovaWebSocketHandler wsHandler;
    private final long shutdownAwaitMs;
    private volatile boolean running = true;

    public ShutdownCoordinator(TaskQueueService queueService,
                               ShutdownFlag shutdownFlag,
                               NovaWebSocketHandler wsHandler,
                               @Value("${nova.task.request-timeout-ms:1800000}") long shutdownAwaitMs) {
        this.queueService = queueService;
        this.shutdownFlag = shutdownFlag;
        this.wsHandler = wsHandler;
        this.shutdownAwaitMs = shutdownAwaitMs;
    }

    @Override
    public void start() {
        // Application already running.
    }

    @Override
    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("[shutdown] 收到停机信号，开始优雅退出");
        shutdownFlag.setShuttingDown(true);
        try {
            wsHandler.closeAllSessions(1001, "Server shutting down");
        } catch (Exception e) {
            log.warn("[shutdown] WS 关闭异常", e);
        }
        queueService.shutdown(shutdownAwaitMs);
        log.info("[shutdown] 优雅退出完成");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Runs after the web server has stopped accepting new requests. */
    @Override
    public int getPhase() {
        return Integer.MIN_VALUE + 100;
    }
}
