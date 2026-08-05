package com.nova.studio.ws;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0.4 — WebSocket minimal protocol, end-to-end.
 *
 * <p>Boots a WS-only Spring context (no datasource/redis/flyway) on a random port
 * and drives it with a real {@link java.net.http.WebSocket} client, verifying the
 * protocol table (ARCH §E.3): ping→pong, subscribeTasks immediate push, task
 * broadcast over WS, subscribeQueue immediate queueStatus, error codes, and the
 * server heartbeat terminate path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = NovaWebSocketE2ETest.WsTestApp.class,
        properties = {
                "nova.ws.heartbeat-interval-ms=600",
                "nova.ws.pong-grace-ms=300",
                "nova.ws.max-heartbeat-misses=2",
                "NOVA_JWT_SECRET=test-secret-test-secret-test-secret-test-secret"
        })
class NovaWebSocketE2ETest {

    @LocalServerPort
    int port;

    private final ObjectMapper mapper = new ObjectMapper();

    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataRedisAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import({com.nova.studio.config.WebSocketConfig.class, com.nova.studio.config.WsCoreConfig.class,
            com.nova.studio.config.SecurityConfig.class,
            com.nova.studio.web.SpikeTaskController.class,
            com.nova.studio.auth.JwtService.class})
    static class WsTestApp {
    }

    record Client(WebSocket ws, BlockingQueue<String> messages) {
    }

    private Client connect() {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .pingInterval(Duration.ofMillis(500)) // OkHttp auto-answers protocol pings like a browser
                .build();
        Request request = new Request.Builder()
                .url("ws://localhost:" + port + "/api/nova/ws")
                .build();
        WebSocket ws = httpClient.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                messages.add(text);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                messages.add("__closed__:" + code);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                messages.add("__failure__:" + t);
            }
        });
        return new Client(ws, messages);
    }

    private JsonNode awaitMessage(BlockingQueue<String> messages) throws Exception {
        String raw = messages.poll(10, TimeUnit.SECONDS);
        assertThat(raw).as("expected a WS message").isNotNull();
        return mapper.readTree(raw);
    }

    @Test
    void pingPong() throws Exception {
        Client client = connect();
        client.ws().send("{\"type\":\"ping\"}");
        JsonNode msg = awaitMessage(client.messages());
        assertThat(msg.get("type").asText()).isEqualTo("pong");
        client.ws().close(1000, "done");
    }

    @Test
    void subscribeTasksImmediatePushAndBroadcast() throws Exception {
        Client client = connect();

        // 1) POST a task via the spike controller, then subscribe → immediate push
        var created = postTask(Map.of("id", "spike-e2e-1", "status", "processing"));
        assertThat(created.get("status").asText()).isEqualTo("processing");

        client.ws().send("{\"type\":\"subscribeTasks\",\"taskIds\":[\"spike-e2e-1\"]}");
        JsonNode push = awaitMessage(client.messages());
        assertThat(push.get("type").asText()).isEqualTo("task");
        assertThat(push.get("task").get("id").asText()).isEqualTo("spike-e2e-1");
        assertThat(push.get("task").get("status").asText()).isEqualTo("processing");

        // 2) Update the task → server broadcasts over WS to the subscribed socket
        postTask(Map.of("id", "spike-e2e-1", "status", "completed", "resultJson", "{\"urls\":[\"x\"]}"));
        JsonNode broadcast = awaitMessage(client.messages());
        assertThat(broadcast.get("type").asText()).isEqualTo("task");
        assertThat(broadcast.get("task").get("status").asText()).isEqualTo("completed");

        client.ws().close(1000, "done");
    }

    @Test
    void subscribeUnknownTaskGetsExpiredFallback() throws Exception {
        Client client = connect();
        client.ws().send("{\"type\":\"subscribeTasks\",\"taskIds\":[\"does-not-exist\"]}");
        JsonNode msg = awaitMessage(client.messages());
        assertThat(msg.get("type").asText()).isEqualTo("task");
        assertThat(msg.get("task").get("status").asText()).isEqualTo("expired");
        client.ws().close(1000, "done");
    }

    @Test
    void subscribeQueueImmediateStatus() throws Exception {
        Client client = connect();
        client.ws().send("{\"type\":\"subscribeQueue\"}");
        JsonNode msg = awaitMessage(client.messages());
        assertThat(msg.get("type").asText()).isEqualTo("queueStatus");
        assertThat(msg.get("stats").get("acceptingNewTasks").asBoolean()).isTrue();
        client.ws().close(1000, "done");
    }

    @Test
    void invalidJsonReturnsError() throws Exception {
        Client client = connect();
        client.ws().send("not-json");
        JsonNode msg = awaitMessage(client.messages());
        assertThat(msg.get("type").asText()).isEqualTo("error");
        assertThat(msg.get("code").asText()).isEqualTo("INVALID_JSON");
        client.ws().close(1000, "done");
    }

    @Test
    void unknownTypeReturnsError() throws Exception {
        Client client = connect();
        client.ws().send("{\"type\":\"nope\"}");
        JsonNode msg = awaitMessage(client.messages());
        assertThat(msg.get("type").asText()).isEqualTo("error");
        assertThat(msg.get("code").asText()).isEqualTo("UNKNOWN_TYPE");
        client.ws().close(1000, "done");
    }

    @Test
    void heartbeatKeepsResponsiveClientAlive() throws Exception {
        Client client = connect(); // auto-pongs
        // wait for at least 3 server pings (600ms interval) → connection must stay open
        Thread.sleep(2500);
        assertThat(client.messages()).as("connection should not be closed").doesNotContain("__closed__:");
        // and the client can still exchange messages
        client.ws().send("{\"type\":\"ping\"}");
        JsonNode msg = awaitMessage(client.messages());
        assertThat(msg.get("type").asText()).isEqualTo("pong");
        client.ws().close(1000, "done");
    }

    private JsonNode postTask(Map<String, Object> task) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(task, headers);
        ResponseEntity<String> resp = new RestTemplate().postForEntity(
                "http://localhost:" + port + "/api/nova/spike/tasks", entity, String.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
