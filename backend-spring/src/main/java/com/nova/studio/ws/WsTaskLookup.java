package com.nova.studio.ws;

import java.util.Map;
import java.util.UUID;

/**
 * Loads a serialized task message for WS pushes (subscribeTasks immediate push
 * and task broadcasts). Implemented by the DB-backed lookup in production; a
 * plain in-memory fallback is provided for WS-only test contexts.
 */
public interface WsTaskLookup {

    /** Serialized task map ({@code NovaTaskResponse} shape); {@code null} when the task does not exist. */
    Map<String, Object> loadTaskMessage(String taskId);

    /** Task owner (null = system/legacy). Used for WS subscription isolation (T2.2). */
    default UUID findOwner(String taskId) {
        return null;
    }
}
