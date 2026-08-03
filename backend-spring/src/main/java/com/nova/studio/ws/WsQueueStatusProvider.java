package com.nova.studio.ws;

import java.util.Map;

/**
 * Supplies the queue-status payload for WS {@code subscribeQueue} pushes and
 * the throttled queue broadcast. Production implementation reads live DB counts
 * (see {@code task.QueueStatsService}); a trivial in-memory default exists for
 * WS-only test contexts.
 */
@FunctionalInterface
public interface WsQueueStatusProvider {

    /** Queue stats map matching the frontend {@code NovaQueueStatus} shape. */
    Map<String, Object> getQueueStatus();
}
