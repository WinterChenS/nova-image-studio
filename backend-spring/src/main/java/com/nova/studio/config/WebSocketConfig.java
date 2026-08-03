package com.nova.studio.config;

import com.nova.studio.ws.NovaWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket wiring for the M0 spike: registers {@link NovaWebSocketHandler} at
 * {@code /api/nova/ws} (native Spring WebSocket, no STOMP — ADR-3).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final NovaWebSocketHandler novaWebSocketHandler;

    public WebSocketConfig(NovaWebSocketHandler novaWebSocketHandler) {
        this.novaWebSocketHandler = novaWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(novaWebSocketHandler, "/api/nova/ws").setAllowedOrigins("*");
    }
}
