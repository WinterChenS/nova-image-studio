package com.nova.studio.auth;

import java.util.UUID;

/**
 * Authenticated principal — parsed from a JWT by {@link JwtAuthenticationFilter}
 * (HTTP) or {@link com.nova.studio.ws.WsAuthHandshakeInterceptor} (WS). Serves
 * as the Spring Security principal ({@code @AuthenticationPrincipal}); {@code
 * null} means anonymous.
 */
public record AuthUser(UUID id, String username, String role) {

    public boolean isAdmin() {
        return "admin".equals(role);
    }
}
