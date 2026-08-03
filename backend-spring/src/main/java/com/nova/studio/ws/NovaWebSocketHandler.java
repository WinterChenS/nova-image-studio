package com.nova.studio.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Nova WebSocket handler (T1.7) — full protocol replica of the Node backend's
 * {@code setupWebSocketServer} / {@code handleClientMessage} ({@code server.js}),
 * driven by the frontend {@code ccode-task-socket.ts} contract (ARCH §E.3):
 * <ul>
 *   <li>{@code subscribeTasks} — ≤200 ids/message, ≤500 ids/socket, immediate
 *       push, terminal auto-unsubscribe, unknown id → expired fallback;</li>
 *   <li>{@code unsubscribeTasks} / {@code subscribeQueue} (immediate push) /
 *       {@code unsubscribeQueue} / {@code ping}→{@code pong};</li>
 *   <li>error codes INVALID_JSON / INVALID_TYPE / UNKNOWN_TYPE;</li>
 *   <li>server heartbeat: protocol ping every {@code heartbeatIntervalMs}, 10s
 *       grace, terminate after {@code maxHeartbeatMisses} missed pongs;</li>
 *   <li>queue broadcasts throttled to {@code queueBroadcastThrottleMs} (Node 200ms
 *       debounce via setTimeout).</li>
 * </ul>
 * Implements {@link TaskEventBroadcaster} so the task pipeline pushes task
 * updates and queue stats without depending on WS classes.
 */
public class NovaWebSocketHandler extends TextWebSocketHandler implements TaskEventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(NovaWebSocketHandler.class);

    public static final String MSG_TASK = "task";
    public static final String MSG_QUEUE_STATUS = "queueStatus";
    public static final String MSG_PONG = "pong";
    public static final String MSG_ERROR = "error";

    private final ObjectMapper objectMapper;
    private final TaskRegistry taskRegistry;
    private final WsTaskLookup taskLookup;
    private final WsQueueStatusProvider queueStatusProvider;

    private final int maxTaskIdsPerMessage;
    private final int maxSubscriptionsPerSocket;
    private final long heartbeatIntervalMs;
    private final long pongGraceMs;
    private final int maxHeartbeatMisses;
    private final long queueBroadcastThrottleMs;
    private final ScheduledExecutorService scheduler;

    private final Map<String, HeartbeatState> heartbeats = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean queueBroadcastPending = new AtomicBoolean(false);

    record HeartbeatState(WebSocketSession session, long[] lastPongAt, int[] missed) {
    }

    public NovaWebSocketHandler(ObjectMapper objectMapper,
                                TaskRegistry taskRegistry,
                                WsTaskLookup taskLookup,
                                WsQueueStatusProvider queueStatusProvider,
                                int maxTaskIdsPerMessage,
                                int maxSubscriptionsPerSocket,
                                long heartbeatIntervalMs,
                                long pongGraceMs,
                                int maxHeartbeatMisses,
                                long queueBroadcastThrottleMs,
                                ScheduledExecutorService scheduler) {
        this.objectMapper = objectMapper;
        this.taskRegistry = taskRegistry;
        this.taskLookup = taskLookup;
        this.queueStatusProvider = queueStatusProvider;
        this.maxTaskIdsPerMessage = maxTaskIdsPerMessage;
        this.maxSubscriptionsPerSocket = maxSubscriptionsPerSocket;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.pongGraceMs = pongGraceMs;
        this.maxHeartbeatMisses = maxHeartbeatMisses;
        this.queueBroadcastThrottleMs = queueBroadcastThrottleMs;
        this.scheduler = scheduler;
    }

    /** Backwards-compatible constructor (heartbeat unit tests) with in-memory defaults. */
    public NovaWebSocketHandler(ObjectMapper objectMapper,
                                TaskRegistry taskRegistry,
                                int maxTaskIdsPerMessage,
                                int maxSubscriptionsPerSocket,
                                long heartbeatIntervalMs,
                                long pongGraceMs,
                                int maxHeartbeatMisses,
                                ScheduledExecutorService scheduler) {
        this(objectMapper, taskRegistry, taskRegistry::get, defaultQueueStatusProvider(),
                maxTaskIdsPerMessage, maxSubscriptionsPerSocket, heartbeatIntervalMs, pongGraceMs,
                maxHeartbeatMisses, 200, scheduler);
    }

    public static WsQueueStatusProvider defaultQueueStatusProvider() {
        return () -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("concurrencyLimit", 50);
            stats.put("configuredConcurrency", 50);
            stats.put("processingCount", 0);
            stats.put("queuedCount", 0);
            stats.put("pendingCount", 0);
            stats.put("acceptingNewTasks", true);
            return stats;
        };
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
        try {
            session.sendMessage(new PingMessage());
        } catch (Exception e) {
            log.debug("[ws] heartbeat ping failed session={}: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String raw = message.getPayload();
        JsonNode msg = parseJson(objectMapper, raw);
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
            case "subscribeQueue" -> handleSubscribeQueue(session);
            case "unsubscribeQueue" -> taskRegistry.unsubscribeQueue(session.getId());
            case "ping" -> send(session, Map.of("type", MSG_PONG));
            default -> send(session, error("UNKNOWN_TYPE", "未知的 type: " + type));
        }
    }

    private void handleSubscribeTasks(WebSocketSession session, JsonNode taskIdsNode) {
        if (taskIdsNode == null || !taskIdsNode.isArray()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        taskIdsNode.forEach(n -> ids.add(n.isTextual() ? n.asText() : null));
        List<Map<String, Object>> pushed = taskRegistry.subscribe(session.getId(), ids,
                maxTaskIdsPerMessage, maxSubscriptionsPerSocket);
        for (Map<String, Object> task : pushed) {
            sendTask(session, task);
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

    private void handleSubscribeQueue(WebSocketSession session) {
        taskRegistry.subscribeQueue(session.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", MSG_QUEUE_STATUS);
        payload.put("stats", queueStatusProvider.getQueueStatus());
        send(session, payload);
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

    // ===== TaskEventBroadcaster =====

    /** Loads the task by id and broadcasts it to every subscribed socket. */
    @Override
    public void broadcastTask(String taskId) {
        broadcastTaskMessage(resolveTask(taskId));
    }

    /** Broadcasts an already-serialized task message to its subscribers (spike/test path). */
    public void broadcastTaskMessage(Map<String, Object> task) {
        List<String> targets = taskRegistry.broadcast(task);
        for (String sessionId : targets) {
            WebSocketSession session = sessions.get(sessionId);
            if (session != null) {
                sendTask(session, task);
            }
        }
    }

    /** Hardcoded expired push before deletion (Node broadcastTaskExpired). */
    @Override
    public void broadcastTaskExpired(String taskId) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("id", taskId);
        task.put("status", TaskRegistry.STATUS_EXPIRED);
        task.put("error", TaskRegistry.EXPIRED_ERROR);
        List<String> targets = taskRegistry.broadcast(task);
        for (String sessionId : targets) {
            WebSocketSession session = sessions.get(sessionId);
            if (session != null) {
                sendTask(session, task);
            }
        }
    }

    /** Debounced queue-status broadcast (Node 200ms setTimeout). */
    @Override
    public void broadcastQueueStatus() {
        queueBroadcastPending.set(true);
        scheduler.schedule(this::flushQueueBroadcast, queueBroadcastThrottleMs, TimeUnit.MILLISECONDS);
    }

    private void flushQueueBroadcast() {
        if (!queueBroadcastPending.compareAndSet(true, false)) {
            return;
        }
        Set<String> subscribers = taskRegistry.queueSubscribers();
        if (subscribers.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", MSG_QUEUE_STATUS);
        payload.put("stats", queueStatusProvider.getQueueStatus());
        for (String sessionId : subscribers) {
            WebSocketSession session = sessions.get(sessionId);
            if (session != null) {
                send(session, payload);
            }
        }
    }

    // ===== helpers =====

    private Map<String, Object> resolveTask(String taskId) {
        if (taskLookup != null) {
            Map<String, Object> task = taskLookup.loadTaskMessage(taskId);
            if (task != null) {
                return task;
            }
        }
        Map<String, Object> task = taskRegistry.get(taskId);
        if (task != null) {
            return task;
        }
        Map<String, Object> expired = new LinkedHashMap<>();
        expired.put("id", taskId);
        expired.put("status", TaskRegistry.STATUS_EXPIRED);
        expired.put("error", TaskRegistry.EXPIRED_ERROR);
        return expired;
    }

    private void sendTask(WebSocketSession session, Map<String, Object> task) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", MSG_TASK);
        payload.put("task", task);
        send(session, payload);
    }

    private Map<String, Object> error(String code, String message) {
        return Map.of("type", MSG_ERROR, "code", code, "message", message);
    }

    private void send(WebSocketSession session, Object payload) {
        String json = toJson(objectMapper, payload);
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

    /** Graceful WS close on shutdown (Node closeWebSocketServer). */
    public void closeAllSessions(int code, String reason) {
        for (WebSocketSession session : sessions.values()) {
            try {
                if (session.isOpen()) {
                    session.close(new CloseStatus(code, reason));
                }
            } catch (Exception ignored) {
                try {
                    session.close();
                } catch (Exception ignored2) {
                    // ignore
                }
            }
        }
    }

    private static String toJson(ObjectMapper mapper, Object payload) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonNode parseJson(ObjectMapper mapper, String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
