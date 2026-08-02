package com.nova.studio.config;

import tools.jackson.databind.ObjectMapper;
import com.nova.studio.ws.NovaWebSocketHandler;
import com.nova.studio.ws.TaskRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Core WS beans (handler, registry, heartbeat scheduler). Kept in a separate
 * configuration class from {@link WebSocketConfig} so the handler can be
 * constructed independently of the WebSocket registration callback.
 */
@Configuration
public class WsCoreConfig {

    @Bean
    public static TaskRegistry taskRegistry() {
        return new TaskRegistry();
    }

    @Bean
    public ScheduledExecutorService wsHeartbeatScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public NovaWebSocketHandler novaWebSocketHandler(ObjectMapper objectMapper,
                                                     TaskRegistry taskRegistry,
                                                     ScheduledExecutorService scheduler,
                                                     @Value("${nova.ws.max-task-ids-per-message:200}") int maxTaskIdsPerMessage,
                                                     @Value("${nova.ws.max-subscriptions-per-socket:500}") int maxSubscriptionsPerSocket,
                                                     @Value("${nova.ws.heartbeat-interval-ms:30000}") long heartbeatIntervalMs,
                                                     @Value("${nova.ws.pong-grace-ms:10000}") long pongGraceMs,
                                                     @Value("${nova.ws.max-heartbeat-misses:2}") int maxHeartbeatMisses) {
        return new NovaWebSocketHandler(objectMapper,
                taskRegistry, maxTaskIdsPerMessage, maxSubscriptionsPerSocket, heartbeatIntervalMs, pongGraceMs,
                maxHeartbeatMisses, scheduler);
    }
}
