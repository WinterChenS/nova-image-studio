package com.nova.studio.ws;

/**
 * Outbound task/queue event hooks used by the task pipeline. Implemented by
 * {@link NovaWebSocketHandler}; consumed by the task services so the queue
 * never depends on the WS classes directly (no circular bean graph).
 */
public interface TaskEventBroadcaster {

    /** Loads the task by id and pushes it to every subscribed socket (terminal → auto-unsubscribe). */
    void broadcastTask(String taskId);

    /** Pushes the hardcoded expired task payload (Node broadcastTaskExpired). */
    void broadcastTaskExpired(String taskId);

    /** Throttled queue-status broadcast to all queue subscribers (Node 200ms debounce). */
    void broadcastQueueStatus();
}
