package com.nova.studio.web;

import com.nova.studio.infra.NormalizedError;
import com.nova.studio.textproxy.TextProxyService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Text proxy endpoint (T1.5) — {@code POST /api/nova/proxy/text}. Port of the
 * Node handler: SSE streaming passthrough when {@code stream} is requested and
 * the upstream is 2xx (headers {@code text/event-stream}, {@code Cache-Control:
 * no-cache}, {@code Connection: keep-alive}, {@code X-Accel-Buffering: no}),
 * otherwise the upstream body is forwarded as JSON with its status; upstream
 * failures map to 502 (timeouts → 504). Writes directly to
 * {@link HttpServletResponse} so the upstream status/headers and the
 * incremental SSE body are forwarded exactly.
 */
@RestController
@RequestMapping("/api/nova/proxy/text")
public class TextProxyController {

    private static final Pattern TIMEOUT_PATTERN = Pattern.compile("(?i)abort|timeout");

    private final TextProxyService textProxyService;
    private final long requestTimeoutMs;
    private final ObjectMapper objectMapper;

    public TextProxyController(TextProxyService textProxyService,
                               @Value("${nova.task.request-timeout-ms:1800000}") long requestTimeoutMs,
                               ObjectMapper objectMapper) {
        this.textProxyService = textProxyService;
        this.requestTimeoutMs = requestTimeoutMs;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public void proxy(@RequestBody(required = false) JsonNode body, HttpServletResponse response) throws IOException {
        String protocol = text(body, "protocol");
        String baseUrl = text(body, "baseUrl");
        String apiKey = text(body, "apiKey");
        String model = text(body, "model");
        boolean stream = body != null && body.has("stream") && body.get("stream").asBoolean(false);
        if (baseUrl == null || apiKey == null) {
            writeJson(response, 400, Map.of("error", "Missing baseUrl or apiKey"));
            return;
        }
        try {
            TextProxyService.Target target = textProxyService.buildTarget(protocol, baseUrl, apiKey, model, stream);
            JsonNode forwarded = TextProxyService.forwardedBody(body);
            TextProxyService.ProxyExchange exchange = textProxyService.exchange(target, forwarded);
            response.setStatus(exchange.status());
            if (exchange.streamed()) {
                response.setContentType("text/event-stream");
                response.setCharacterEncoding("UTF-8");
                response.setHeader("Cache-Control", "no-cache");
                response.setHeader("Connection", "keep-alive");
                response.setHeader("X-Accel-Buffering", "no");
                exchange.transferTo(response.getOutputStream());
                return;
            }
            writeJson(response, exchange.status(), parseJsonOrRaw(exchange.jsonBody(), exchange.status()));
        } catch (Exception e) {
            if (e.getMessage() != null && TIMEOUT_PATTERN.matcher(e.getMessage()).find()) {
                writeJson(response, 504, Map.of("error", "代理请求上游超时"));
                return;
            }
            writeJson(response, 502, Map.of("error", NormalizedError.normalize(e, requestTimeoutMs)));
        }
    }

    private Object parseJsonOrRaw(String bodyText, int status) {
        if (bodyText == null || bodyText.isBlank()) {
            return Map.of("error", "上游返回 " + status);
        }
        try {
            return objectMapper.readValue(bodyText, Object.class);
        } catch (Exception e) {
            return Map.of("error", "上游返回 " + status);
        }
    }

    private void writeJson(HttpServletResponse response, int status, Object payload) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(payload));
    }

    private static String text(JsonNode body, String key) {
        if (body == null || !body.has(key) || !body.get(key).isTextual()) {
            return null;
        }
        return body.get(key).asText();
    }
}
