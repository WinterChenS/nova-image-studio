package com.nova.studio.ws;

import com.nova.studio.auth.JwtService;
import com.nova.studio.auth.AuthUser;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URI;
import java.util.Map;

/**
 * Optional WS handshake auth (T2.5) — browsers cannot set headers on a
 * WebSocket handshake, so the frontend passes the JWT as {@code ?token=}.
 * A valid token is stored in the session attributes; anonymous connections
 * (read-only browsing of legacy/NULL-owner tasks) stay allowed per Q1.
 */
public class WsAuthHandshakeInterceptor implements HandshakeInterceptor {

    public static final String AUTH_USER_ATTR = "authUser";

    private final JwtService jwtService;

    public WsAuthHandshakeInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        URI uri = request.getURI();
        String query = uri == null ? null : uri.getQuery();
        if (query != null) {
            String token = null;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && "token".equals(pair.substring(0, eq))) {
                    token = pair.substring(eq + 1);
                    break;
                }
            }
            AuthUser user = jwtService.parse(token);
            if (user != null) {
                attributes.put(AUTH_USER_ATTR, user);
            }
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    /** Read the authenticated user from a WS session (null when anonymous). */
    public static AuthUser current(org.springframework.web.socket.WebSocketSession session) {
        Object value = session.getAttributes().get(AUTH_USER_ATTR);
        return value instanceof AuthUser user ? user : null;
    }
}
