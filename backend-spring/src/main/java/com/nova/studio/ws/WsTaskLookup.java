package com.nova.studio.ws;

import java.util.Map;

/**
 * Loads a serialized task message for WS pushes (subscribeTasks immediate push
 * and task broadcasts). Implemented by the DB-backed lookup in production; a
 * plain in-memory fallback is provided for WS-only test contexts.
 */
@FunctionalInterface
public interface WsTaskLookup {

    /** Serialized task map ({@code NovaTaskResponse} shape); {@code null} when the task does not exist. */
    Map<String, Object> loadTaskMessage(String taskId);
}
