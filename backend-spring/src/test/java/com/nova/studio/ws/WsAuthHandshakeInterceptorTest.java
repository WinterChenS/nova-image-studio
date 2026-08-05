package com.nova.studio.ws;

import com.nova.studio.auth.AuthUser;
import com.nova.studio.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * M2 T2.5 — WS handshake auth via {@code ?token=} (browsers cannot set headers
 * on the WS handshake): valid token → session attribute, anonymous stays null.
 */
class WsAuthHandshakeInterceptorTest {

    private static final String SECRET = "S3GYs4Qf3sLwAgajjSi4/ADjR00ldw9RX2iwL5NgRr6fGU98/24hTLFHu0JySZG7";

    private final JwtService jwt = new JwtService(SECRET);
    private final WsAuthHandshakeInterceptor interceptor = new WsAuthHandshakeInterceptor(jwt);

    private ServerHttpRequest requestWithQuery(String query) throws Exception {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        URI uri = new URI("ws://localhost/api/nova/ws" + (query != null ? "?" + query : ""));
        org.mockito.Mockito.when(request.getURI()).thenReturn(uri);
        return request;
    }

    @Test
    void attachesUserForValidToken() throws Exception {
        UUID id = UUID.randomUUID();
        String token = jwt.issue(id, "alice", "user");
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(requestWithQuery("token=" + token),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        assertThat(ok).isTrue();
        AuthUser user = (AuthUser) attributes.get(WsAuthHandshakeInterceptor.AUTH_USER_ATTR);
        assertThat(user).isNotNull();
        assertThat(user.id()).isEqualTo(id);
        assertThat(user.username()).isEqualTo("alice");
    }

    @Test
    void anonymousWithoutToken() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(requestWithQuery(null),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        assertThat(ok).isTrue();
        assertThat(attributes.get(WsAuthHandshakeInterceptor.AUTH_USER_ATTR)).isNull();
    }

    @Test
    void rejectsInvalidTokenButStillAcceptsHandshake() throws Exception {
        Map<String, Object> attributes = new HashMap<>();
        boolean ok = interceptor.beforeHandshake(requestWithQuery("token=garbage"),
                mock(ServerHttpResponse.class), mock(WebSocketHandler.class), attributes);
        assertThat(ok).isTrue();
        assertThat(attributes.get(WsAuthHandshakeInterceptor.AUTH_USER_ATTR)).isNull();
    }
}
