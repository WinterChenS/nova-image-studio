package com.nova.studio.imagegen;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Custom OpenAI-compatible image client for the {@code partial_images} streaming
 * endpoint (T0.3 spike, ADR-4). Spring AI's {@code OpenAiImageModel} is blocking
 * and has no partial/stream support, so this WebClient-based client replicates
 * the Node backend's streaming call:
 *
 * <pre>
 * POST {baseUrl}/v1/images/generations
 * {"model":..., "prompt":..., "n":1, "stream":true, "partial_images":N,
 *  "size":..., "quality":..., "output_format":"png", ...}
 * </pre>
 *
 * <p>Response handling (ported from Node {@code parseGptImageResponse}):
 * <ul>
 *   <li>{@code text/event-stream} → SSE parsed, partial events skipped, final
 *       image extracted (non-streaming fallback not needed for SSE)</li>
 *   <li>any other content type → treated as JSON body and extracted
 *       (non-streaming fallback path)</li>
 * </ul>
 */
public class PartialImagesClient {

    private static final Logger log = LoggerFactory.getLogger(PartialImagesClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public PartialImagesClient(String baseUrl, String apiKey, WebClient.Builder webClientBuilder,
                               ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.objectMapper = objectMapper;
    }

    public record Request(String model, String prompt, int n, Integer partialImages, String size,
                          String quality, String style, String background, Integer width, Integer height) {
    }

    /** Calls the streaming endpoint and returns the final image payload (b64 or {@code URL:...}). */
    public String generate(Request request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("prompt", request.prompt());
        body.put("n", request.n());
        if (request.partialImages() != null && request.partialImages() > 0) {
            body.put("stream", true);
            body.put("partial_images", request.partialImages());
        }
        if (StringUtils.hasText(request.size())) {
            body.put("size", request.size());
        }
        if (StringUtils.hasText(request.quality())) {
            body.put("quality", request.quality());
        }
        if (StringUtils.hasText(request.background())) {
            body.put("background", request.background());
        }
        if (StringUtils.hasText(request.style())) {
            body.put("style", request.style());
        }
        body.put("output_format", "png");
        if (request.width() != null) {
            body.put("width", request.width());
        }
        if (request.height() != null) {
            body.put("height", request.height());
        }

        log.debug("[partial-images] POST /v1/images/generations model={} stream={} partial={}",
                request.model(), body.containsKey("stream"), request.partialImages());

        ResponseEntity<String> response = webClient.post()
                .uri("/v1/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block();

        if (response == null || response.getBody() == null) {
            throw new IllegalStateException("上游返回空响应");
        }
        String responseBody = response.getBody();
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        if (contentType != null && contentType.toLowerCase().contains("text/event-stream")) {
            return ImagePayloadExtractor.extractFromEventStream(responseBody, objectMapper);
        }
        try {
            var json = objectMapper.readTree(responseBody);
            String error = errorMessage(json);
            if (error != null) {
                throw new IllegalStateException(error);
            }
            return ImagePayloadExtractor.extract(json);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("响应 JSON 格式无效", e);
        }
    }


    private String errorMessage(tools.jackson.databind.JsonNode json) {
        if (json == null || !json.isObject()) {
            return null;
        }
        var error = json.get("error");
        if (error != null) {
            if (error.isTextual()) {
                return error.asText();
            }
            if (error.isObject()) {
                var m = error.get("message");
                if (m != null && m.isTextual()) {
                    return m.asText();
                }
            }
        }
        return null;
    }
}
