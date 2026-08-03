package com.nova.studio.integration;

import com.nova.studio.storage.ImageStorageService;
import com.nova.studio.task.TaskService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1.11 (Spring side) — full task lifecycle over the real HTTP surface with a
 * mocked OpenAI-compatible upstream: create → queue → processing → completed,
 * image hosting headers, queue-status shape, ack renewal, model-list proxy and
 * text proxy passthrough. Runs only when {@code DB_HOST} is set (external PG).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "nova.ws.heartbeat-interval-ms=1000",
                "nova.ws.pong-grace-ms=500"
        })
@EnabledIfEnvironmentVariable(named = "DB_HOST", matches = ".+")
class TaskApiE2EIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    private TaskService taskService;
    @Autowired
    private ImageStorageService imageStorageService;

    private MockWebServer upstream;
    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        upstream = new MockWebServer();
        upstream.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        upstream.shutdown();
    }

    private Map<String, Object> taskBody(String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", "sk-e2e");
        body.put("baseUrl", upstream.url("/").toString());
        body.put("protocol", "openai");
        body.put("mode", "text-to-image");
        body.put("prompt", prompt);
        body.put("outputSize", "1K");
        body.put("aspectRatio", "1:1");
        body.put("temperature", 1.0);
        body.put("model", "gpt-image-1");
        body.put("gptImageQuality", "high");
        body.put("parallelCount", 1);
        body.put("images", java.util.List.of());
        return body;
    }

    private JsonNode post(String url, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.postForEntity(url, new HttpEntity<>(body, headers), String.class);
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode get(String url) {
        ResponseEntity<String> resp = rest.getForEntity(url, String.class);
        try {
            return mapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void taskLifecycleCompletesAndImageIsHosted() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"b64_json\":\"" + java.util.Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, 4}) + "\"}]}"));

        JsonNode created = post("http://localhost:" + port + "/api/nova/tasks", taskBody("a red apple"));
        String taskId = created.get("taskId").asText();
        assertThat(taskId).isNotBlank();

        // 202 task created; queue-status exposes it
        JsonNode queueStatus = get("http://localhost:" + port + "/api/nova/queue-status");
        assertThat(queueStatus.get("acceptingNewTasks").asBoolean()).isTrue();
        assertThat(queueStatus.has("concurrencyLimit")).isTrue();
        assertThat(queueStatus.has("displayQueued")).isTrue();
        assertThat(queueStatus.has("serverMessage")).isFalse();

        // wait for completion
        JsonNode task = awaitCompleted(taskId);
        assertThat(task.get("status").asText()).isEqualTo("completed");
        assertThat(task.get("result").get("images").get(0).asText()).isEqualTo("URL:/api/nova/images/" + taskId + "/0");
        assertThat(task.get("createdAt")).isNotNull();
        assertThat(task.get("completedAt")).isNotNull();
        assertThat(task.get("expiresAt")).isNotNull();

        // image hosted with Node-compatible headers
        ResponseEntity<byte[]> image = rest.getForEntity(
                "http://localhost:" + port + "/api/nova/images/" + taskId + "/0", byte[].class);
        assertThat(image.getStatusCode().value()).isEqualTo(200);
        assertThat(image.getHeaders().getFirst("Cache-Control")).isEqualTo("private, max-age=3600");
        assertThat(image.getHeaders().getContentType().toString()).startsWith("image/png");
        assertThat(image.getBody()).containsExactly(1, 2, 3, 4);

        // ack renews
        JsonNode ack = post("http://localhost:" + port + "/api/nova/tasks/" + taskId + "/ack", Map.of());
        assertThat(ack.get("ok").asBoolean()).isTrue();

        // upstream saw the streaming-attempt request (JSON fallback path)
        RecordedRequest req = upstream.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/v1/images/generations");
        JsonNode reqBody = mapper.readTree(req.getBody().readUtf8());
        assertThat(reqBody.get("model").asText()).isEqualTo("gpt-image-1");
        assertThat(reqBody.get("size").asText()).isEqualTo("1024x1024");

        taskService.deleteTask(taskId);
        imageStorageService.deleteTaskImageFiles(taskId);
    }

    @Test
    void modelListProxyForwardsUpstream() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"data\":[{\"id\":\"gpt-image-1\"}]}"));

        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + port + "/api/nova/proxy/models?baseUrl=" + upstream.url("/")
                        + "&apiKey=sk-x&protocol=openai", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        JsonNode data = mapper.readTree(resp.getBody());
        assertThat(data.get("data").get(0).get("id").asText()).isEqualTo("gpt-image-1");
    }

    @Test
    void textProxyNonStreamPassthrough() throws Exception {
        upstream.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("protocol", "openai-chat-completions");
        body.put("baseUrl", upstream.url("/").toString());
        body.put("apiKey", "sk-x");
        body.put("model", "gpt-4o");
        body.put("stream", false);
        body.put("messages", java.util.List.of(Map.of("role", "user", "content", "hello")));

        JsonNode resp = post("http://localhost:" + port + "/api/nova/proxy/text", body);
        assertThat(resp.get("choices")).as("response: %s", resp).isNotNull();
        assertThat(resp.get("choices").get(0).get("message").get("content").asText()).isEqualTo("hi");

        RecordedRequest req = upstream.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        JsonNode forwarded = mapper.readTree(req.getBody().readUtf8());
        assertThat(forwarded.has("protocol")).isFalse();
        assertThat(forwarded.has("apiKey")).isFalse();
        assertThat(forwarded.get("messages").get(0).get("content").asText()).isEqualTo("hello");
    }

    @Test
    void invalidTaskPayloadReturns400() {
        Map<String, Object> body = taskBody("x");
        body.put("protocol", "bogus");
        RestTemplate lenient = new RestTemplate();
        lenient.setErrorHandler(new org.springframework.web.client.ResponseErrorHandler() {
            @Override
            public boolean hasError(org.springframework.http.client.ClientHttpResponse response) {
                return false;
            }

            @Override
            public void handleError(java.net.URI url, org.springframework.http.HttpMethod method,
                                    org.springframework.http.client.ClientHttpResponse response) {
            }
        });
        ResponseEntity<String> resp = lenient.exchange(
                "http://localhost:" + port + "/api/nova/tasks",
                org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers()),
                String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).contains("协议类型无效");
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode awaitCompleted(String taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode task = get("http://localhost:" + port + "/api/nova/tasks/" + taskId);
            String status = task.get("status").asText();
            if ("completed".equals(status) || "failed".equals(status)) {
                return task;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("task did not finish within 20s: " + taskId);
    }
}
