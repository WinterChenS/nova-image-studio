package com.nova.studio.task;

import org.springframework.stereotype.Component;

/**
 * Shared graceful-shutdown flag: set when the backend starts shutting down so
 * {@code acceptingNewTasks} flips to false (Node's {@code isShuttingDown}).
 * Kept as a tiny component so queue stats (read by REST/WS) and the queue
 * worker (writer) never form a circular dependency.
 */
@Component
public class ShutdownFlag {

    private volatile boolean shuttingDown;

    public boolean isShuttingDown() {
        return shuttingDown;
    }

    public void setShuttingDown(boolean shuttingDown) {
        this.shuttingDown = shuttingDown;
    }
}
