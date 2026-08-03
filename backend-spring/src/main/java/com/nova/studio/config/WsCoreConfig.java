package com.nova.studio.config;

import tools.jackson.databind.ObjectMapper;
import com.nova.studio.ws.NovaWebSocketHandler;
import com.nova.studio.ws.TaskRegistry;
import com.nova.studio.ws.WsQueueStatusProvider;
import com.nova.studio.ws.WsTaskLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Core WS beans (handler, registry, heartbeat scheduler). Kept separate from
 * {@link WebSocketConfig} so the handler can be constructed independently of
 * the WebSocket registration callback.
 *
 * <p>{@code WsTaskLookup} / {@code WsQueueStatusProvider} are resolved to the
 * production implementations ({@code task.TaskLookupService} /
 * {@code task.QueueStatsService}) in the full app; WS-only test contexts get
 * the in-memory fallbacks below.
 */
@Configuration
public class WsCoreConfig {

    @Bean
    public static TaskRegistry taskRegistry() {
        return new TaskRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(WsTaskLookup.class)
    public WsTaskLookup fallbackTaskLookup(TaskRegistry taskRegistry) {
        return taskRegistry::get;
    }

    @Bean
    @ConditionalOnMissingBean(WsQueueStatusProvider.class)
    public WsQueueStatusProvider fallbackQueueStatusProvider() {
        return NovaWebSocketHandler.defaultQueueStatusProvider();
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
                                                     WsTaskLookup taskLookup,
                                                     WsQueueStatusProvider queueStatusProvider,
                                                     ScheduledExecutorService scheduler,
                                                     @Value("${nova.ws.max-task-ids-per-message:200}") int maxTaskIdsPerMessage,
                                                     @Value("${nova.ws.max-subscriptions-per-socket:500}") int maxSubscriptionsPerSocket,
                                                     @Value("${nova.ws.heartbeat-interval-ms:30000}") long heartbeatIntervalMs,
                                                     @Value("${nova.ws.pong-grace-ms:10000}") long pongGraceMs,
                                                     @Value("${nova.ws.max-heartbeat-misses:2}") int maxHeartbeatMisses,
                                                     @Value("${nova.ws.queue-broadcast-throttle-ms:200}") long queueBroadcastThrottleMs) {
        return new NovaWebSocketHandler(objectMapper,
                taskRegistry, taskLookup, queueStatusProvider,
                maxTaskIdsPerMessage, maxSubscriptionsPerSocket, heartbeatIntervalMs, pongGraceMs,
                maxHeartbeatMisses, queueBroadcastThrottleMs, scheduler);
    }
}
