package com.nova.studio.ws;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Heartbeat terminate-path unit test: a session that never answers protocol
 * pongs must be closed after {@code maxHeartbeatMisses} misses (ARCH §E.3:
 * 30s ping + 10s grace + 2 misses terminate; intervals shortened here).
 */
class NovaWebSocketHandlerHeartbeatTest {

    private NovaWebSocketHandler handler;
    private ScheduledExecutorService scheduler;

    private NovaWebSocketHandler newHandler(long intervalMs, long graceMs, int misses) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "heartbeat-test");
            t.setDaemon(true);
            return t;
        });
        return new NovaWebSocketHandler(new ObjectMapper(), new TaskRegistry(),
                200, 500, intervalMs, graceMs, misses, scheduler);
    }

    @Test
    void terminatesSessionThatNeverPongs() throws Exception {
        handler = newHandler(100, 50, 2);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);

        AtomicInteger closeCalls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            closeCalls.incrementAndGet();
            return null;
        }).when(session).close(any(CloseStatus.class));

        handler.afterConnectionEstablished(session);
        Thread.sleep(700); // ≈ 7 beats × 100ms → at least 2 misses beyond 150ms window
        assertThat(closeCalls.get()).as("unresponsive session must be terminated").isGreaterThan(0);

        scheduler.shutdownNow();
    }

    @Test
    void pongsResetMissCounter() throws Exception {
        handler = newHandler(100, 50, 2);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("s1");
        when(session.isOpen()).thenReturn(true);

        AtomicInteger closeCalls = new AtomicInteger();
        org.mockito.Mockito.doAnswer(inv -> {
            closeCalls.incrementAndGet();
            return null;
        }).when(session).close(any(CloseStatus.class));

        handler.afterConnectionEstablished(session);
        // respond to each ping by delivering a pong to the handler
        org.mockito.Mockito.doAnswer(inv -> {
            handler.handlePongMessage(session, null);
            return null;
        }).when(session).sendMessage(any(org.springframework.web.socket.PingMessage.class));

        Thread.sleep(700);
        assertThat(closeCalls.get()).as("responsive session must stay alive").isZero();
        verify(session, atLeastOnce()).sendMessage(any(org.springframework.web.socket.PingMessage.class));

        scheduler.shutdownNow();
    }
}
