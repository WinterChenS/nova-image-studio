package com.nova.studio.config;

import com.nova.studio.auth.JwtService;
import com.nova.studio.ws.NovaWebSocketHandler;
import com.nova.studio.ws.WsAuthHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket wiring: registers {@link NovaWebSocketHandler} at
 * {@code /api/nova/ws} (native Spring WebSocket, no STOMP — ADR-3) with the
 * optional JWT handshake interceptor (T2.5, {@code ?token=}).
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final NovaWebSocketHandler novaWebSocketHandler;
    private final JwtService jwtService;

    public WebSocketConfig(NovaWebSocketHandler novaWebSocketHandler, JwtService jwtService) {
        this.novaWebSocketHandler = novaWebSocketHandler;
        this.jwtService = jwtService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(novaWebSocketHandler, "/api/nova/ws")
                .addInterceptors(new WsAuthHandshakeInterceptor(jwtService))
                .setAllowedOrigins("*");
    }
}
