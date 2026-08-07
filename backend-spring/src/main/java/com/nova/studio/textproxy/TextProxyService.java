package com.nova.studio.textproxy;

import com.nova.studio.imagegen.ImageGenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Text AI proxy (T1.5) — port of the Node backend's {@code POST
 * /api/nova/proxy/text} ({@code backend/server.js}): raw body passthrough to
 * one of the 4 text protocols (google-gemini / anthropic-messages /
 * openai-chat-completions / openai-responses) with SSE streaming and
 * non-streaming JSON responses. The raw passthrough is deliberately kept
 * (ADR-2 / ARCH B.2 #6): Agent tool calls and other complex bodies must not be
 * mangled by a typed client.
 *
 * <p>Implemented on {@link java.net.http.HttpClient} (JDK 21) rather than
 * Spring WebClient so the upstream body can be handed to the servlet
 * {@code OutputStream} incrementally (true SSE passthrough, first byte as it
 * arrives) while still forwarding the exact upstream status — the same control
 * the Node {@code fetch} + {@code reader} loop has. The 30min request timeout
 * matches Node's {@code fetchWithTimeout}.
 */
@Service
public class TextProxyService {

    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final long requestTimeoutMs;
    private final HttpClient httpClient;

    public TextProxyService(tools.jackson.databind.ObjectMapper objectMapper,
                            @Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs) {
        this.objectMapper = objectMapper;
        this.requestTimeoutMs = requestTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** Upstream target: URL + auth headers + whether streaming was requested. */
    public record Target(String url, Map<String, String> headers, boolean streamRequested) {
    }

    /**
     * Exchange result: when {@code stream} is non-null the upstream body is
     * available for incremental passthrough (SSE); otherwise {@code jsonBody}
     * carries the full text.
     */
    public record ProxyExchange(int status, InputStream stream, String jsonBody) {
        public boolean streamed() {
            return stream != null;
        }

        public void transferTo(OutputStream out) throws IOException {
            try (InputStream in = stream) {
                in.transferTo(out);
            }
        }
    }

    /** Node handleApi POST /api/nova/proxy/text — builds target URL + auth headers. */
    public Target buildTarget(String protocol, String baseUrl, String apiKey, String model, boolean stream) {
        String normalizedBaseUrl = ImageGenService.normalizeProtocolBaseUrl(protocol, baseUrl);
        Map<String, String> authHeaders = new LinkedHashMap<>();
        authHeaders.put("Content-Type", "application/json");
        String targetUrl;
        if ("google".equals(protocol) || "google-gemini".equals(protocol)) {
            targetUrl = stream
                    ? normalizedBaseUrl + "/v1beta/models/" + encode(model) + ":streamGenerateContent?alt=sse"
                    : normalizedBaseUrl + "/v1beta/models/" + encode(model) + ":generateContent";
            authHeaders.put("x-goog-api-key", apiKey);
            authHeaders.put("Authorization", "Bearer " + apiKey);
        } else if ("anthropic-messages".equals(protocol)) {
            targetUrl = normalizedBaseUrl + "/v1/messages";
            authHeaders.put("x-api-key", apiKey);
            authHeaders.put("anthropic-version", "2023-06-01");
        } else if ("openai-chat-completions".equals(protocol)) {
            targetUrl = normalizedBaseUrl + "/v1/chat/completions";
            authHeaders.put("Authorization", "Bearer " + apiKey);
        } else {
            targetUrl = normalizedBaseUrl + "/v1/responses";
            authHeaders.put("Authorization", "Bearer " + apiKey);
        }
        if (stream) {
            authHeaders.put("Accept", "text/event-stream");
        }
        return new Target(targetUrl, authHeaders, stream);
    }

    /** Node forwardedBody: requestBody wins, else the cleaned input minus control fields. */
    public static JsonNode forwardedBody(JsonNode body) {
        if (body != null && body.has("requestBody") && body.get("requestBody").isObject()) {
            return body.get("requestBody");
        }
        if (body == null) {
            return null;
        }
        var cleaned = ((tools.jackson.databind.node.ObjectNode) body).deepCopy();
        cleaned.remove("protocol");
        cleaned.remove("baseUrl");
        cleaned.remove("apiKey");
        cleaned.remove("model");
        cleaned.remove("modelId");
        cleaned.remove("stream");
        cleaned.remove("requestBody");
        return cleaned;
    }

    /**
     * Performs the upstream exchange (single request, blocking). When streaming
     * was requested and the upstream is 2xx the body is returned as an open
     * {@link InputStream} for incremental passthrough; otherwise the body is
     * fully read and returned as text so the caller can forward it as JSON with
     * the upstream status (Node {@code sendJson(res, status, ...)}).
     */
    public ProxyExchange exchange(Target target, JsonNode forwardedBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(target.url()))
                    .timeout(Duration.ofMillis(requestTimeoutMs))
                    .headers(headersToArray(target.headers()))
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(forwardedBody), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            boolean ok = status >= 200 && status < 300;
            if (target.streamRequested() && ok) {
                return new ProxyExchange(status, response.body(), null);
            }
            try (InputStream in = response.body()) {
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                return new ProxyExchange(status, null, text);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private String toJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private static String[] headersToArray(Map<String, String> headers) {
        String[] array = new String[headers.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            array[i++] = entry.getKey();
            array[i++] = entry.getValue();
        }
        return array;
    }

    private static String encode(String model) {
        return java.net.URLEncoder.encode(String.valueOf(model), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
