package com.nova.studio.auth;

import java.util.UUID;

/**
 * Authenticated principal — parsed from a JWT by {@link AuthFilter} (HTTP) or
 * {@link com.nova.studio.ws.WsAuthHandshakeInterceptor} (WS). Attached to the
 * request/session as {@code authUser}; {@code null} means anonymous.
 */
public record AuthUser(UUID id, String username, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
