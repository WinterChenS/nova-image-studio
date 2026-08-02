package com.nova.studio.ws;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Nova WebSocket handler — minimal protocol replica (M0 spike, T0.4).
 *
 * <p>Message protocol (1:1 with the Node backend, see ARCH §E.3 and
 * {@code frontend/src/lib/ccode-task-socket.ts}):
 * <ul>
 *   <li>C→S {@code {"type":"subscribeTasks","taskIds":[...]}} — immediate task push,
 *       ≤ {@code maxTaskIdsPerMessage} ids, ≤ {@code maxSubscriptionsPerSocket} per socket,
 *       terminal tasks auto-unsubscribed</li>
 *   <li>C→S {@code {"type":"unsubscribeTasks","taskIds":[...]}}</li>
 *   <li>C→S {@code {"type":"subscribeQueue"}} — immediate queueStatus push</li>
 *   <li>C→S {@code {"type":"unsubscribeQueue"}}</li>
 *   <li>C→S {@code {"type":"ping"}} → S→C {@code {"type":"pong"}}</li>
 *   <li>S→C {@code {"type":"error","code","message"}} — INVALID_JSON / INVALID_TYPE / UNKNOWN_TYPE</li>
 *   <li>Server heartbeat: ping every {@code heartbeatIntervalMs}; terminate after
 *       {@code maxHeartbeatMisses} missed pongs beyond grace</li>
 * </ul>
 */
public class NovaWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(NovaWebSocketHandler.class);

    public static final String MSG_TASK = "task";
    public static final String MSG_QUEUE_STATUS = "queueStatus";
    public static final String MSG_PONG = "pong";
    public static final String MSG_ERROR = "error";

    private final ObjectMapper objectMapper;
    private final TaskRegistry taskRegistry;

    private final int maxTaskIdsPerMessage;
    private final int maxSubscriptionsPerSocket;
    private final long heartbeatIntervalMs;
    private final long pongGraceMs;
    private final int maxHeartbeatMisses;
    private final ScheduledExecutorService scheduler;

    private final Map<String, HeartbeatState> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    record HeartbeatState(WebSocketSession session, long[] lastPongAt, int[] missed) {
    }

    public NovaWebSocketHandler(ObjectMapper objectMapper,
                                TaskRegistry taskRegistry,
                                int maxTaskIdsPerMessage,
                                int maxSubscriptionsPerSocket,
                                long heartbeatIntervalMs,
                                long pongGraceMs,
                                int maxHeartbeatMisses,
                                ScheduledExecutorService scheduler) {
        this.objectMapper = objectMapper;
        this.taskRegistry = taskRegistry;
        this.maxTaskIdsPerMessage = maxTaskIdsPerMessage;
        this.maxSubscriptionsPerSocket = maxSubscriptionsPerSocket;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.pongGraceMs = pongGraceMs;
        this.maxHeartbeatMisses = maxHeartbeatMisses;
        this.scheduler = scheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        long[] lastPong = {System.currentTimeMillis()};
        int[] missed = {0};
        HeartbeatState state = new HeartbeatState(session, lastPong, missed);
        heartbeats.put(session.getId(), state);

        scheduler.scheduleAtFixedRate(() -> checkHeartbeat(state), heartbeatIntervalMs, heartbeatIntervalMs,
                TimeUnit.MILLISECONDS);
        log.debug("[ws] connected session={}", session.getId());
    }

    private void checkHeartbeat(HeartbeatState state) {
        WebSocketSession session = state.session();
        if (!session.isOpen()) {
            heartbeats.remove(session.getId());
            return;
        }
        if (System.currentTimeMillis() - state.lastPongAt()[0] > heartbeatIntervalMs + pongGraceMs) {
            state.missed()[0] += 1;
            if (state.missed()[0] >= maxHeartbeatMisses) {
                log.warn("[ws] heartbeat missed {}x, terminating session={}", state.missed()[0], session.getId());
                try {
                    session.close(CloseStatus.SESSION_NOT_RELIABLE);
                } catch (Exception ignored) {
                    try {
                        session.close();
                    } catch (Exception ignored2) {
                        // ignore
                    }
                }
                heartbeats.remove(session.getId());
                return;
            }
        }
        // Protocol-level ping: browsers auto-respond with pong (see afterConnectionEstablished
        // + handlePongMessage). The application-level {"type":"ping"}→{"type":"pong"} exchange
        // is handled separately in handleTextMessage for the frontend client.
        try {
            session.sendMessage(new PingMessage());
        } catch (Exception e) {
            log.debug("[ws] heartbeat ping failed session={}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        JsonNode msg = TaskRegistry.parse(objectMapper, raw);
        if (msg == null) {
            send(session, error("INVALID_JSON", "消息不是合法 JSON"));
            return;
        }
        if (!msg.has("type") || !msg.get("type").isTextual()) {
            send(session, error("INVALID_TYPE", "消息缺少 type"));
            return;
        }
        String type = msg.get("type").asText();
        switch (type) {
            case "subscribeTasks" -> handleSubscribeTasks(session, msg.get("taskIds"));
            case "unsubscribeTasks" -> handleUnsubscribeTasks(session, msg.get("taskIds"));
            case "subscribeQueue" -> {
                // queueSubscribers is a no-op set in the spike; immediately push queueStatus.
                Map<String, Object> stats = new java.util.LinkedHashMap<>();
                stats.put("concurrencyLimit", 50);
                stats.put("processingCount", 0);
                stats.put("queuedCount", 0);
                stats.put("pendingCount", 0);
                stats.put("acceptingNewTasks", true);
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("type", MSG_QUEUE_STATUS);
                payload.put("stats", stats);
                send(session, payload);
            }
            case "unsubscribeQueue" -> {
                // no-op in spike (no persistent queue subscription state)
            }
            case "ping" -> send(session, Map.of("type", MSG_PONG));
            default -> send(session, error("UNKNOWN_TYPE", "未知的 type: " + type));
        }
    }

    /** Records protocol-level pongs (browser auto-pong to our heartbeat ping). */
    @Override
    protected void handlePongMessage(WebSocketSession session, org.springframework.web.socket.PongMessage message) {
        HeartbeatState state = heartbeats.get(session.getId());
        if (state != null) {
            state.lastPongAt()[0] = System.currentTimeMillis();
            state.missed()[0] = 0;
        }
    }

    private void handleSubscribeTasks(WebSocketSession session, JsonNode taskIdsNode) {
        if (taskIdsNode == null || !taskIdsNode.isArray()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        taskIdsNode.forEach(n -> ids.add(n.isTextual() ? n.asText() : null));
        List<TaskRegistry.Task> pushed = taskRegistry.subscribe(session.getId(), ids, maxTaskIdsPerMessage,
                maxSubscriptionsPerSocket);
        for (TaskRegistry.Task task : pushed) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("type", MSG_TASK);
            payload.put("task", task.toMessage());
            send(session, payload);
        }
    }

    private void handleUnsubscribeTasks(WebSocketSession session, JsonNode taskIdsNode) {
        if (taskIdsNode == null || !taskIdsNode.isArray()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        taskIdsNode.forEach(n -> {
            if (n.isTextual()) {
                ids.add(n.asText());
            }
        });
        taskRegistry.unsubscribe(session.getId(), ids);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        heartbeats.remove(session.getId());
        sessions.remove(session.getId());
        log.debug("[ws] disconnected session={} status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("[ws] transport error session={}: {}", session.getId(), exception.getMessage());
    }

    /**
     * Registers/updates a task and broadcasts it to every subscribed socket.
     * Used by the spike task controller to demonstrate the subscribe/broadcast path.
     */
    public void broadcastTask(TaskRegistry.Task task) {
        List<String> targets = taskRegistry.broadcast(task);
        for (String sessionId : targets) {
            WebSocketSession session = sessions.get(sessionId);
            if (session != null) {
                Map<String, Object> payload = new java.util.LinkedHashMap<>();
                payload.put("type", MSG_TASK);
                payload.put("task", task.toMessage());
                send(session, payload);
            }
        }
        log.debug("[ws] task broadcast id={} targets={}", task.id(), targets.size());
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("type", MSG_ERROR, "code", code, "message", message);
    }

    private void send(WebSocketSession session, Object payload) {
        String json = TaskRegistry.toJson(objectMapper, payload);
        if (json == null) {
            return;
        }
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (Exception e) {
            log.debug("[ws] send failed session={}: {}", session.getId(), e.getMessage());
        }
    }
}
