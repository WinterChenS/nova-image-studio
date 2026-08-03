package com.nova.studio.imagegen;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grok Imagine image client (T1.3) — custom OpenAI-compatible client, port of
 * the Node backend's {@code createGrokImageRequestInit} / {@code requestGrokImage}
 * ({@code backend/server.js}). Spring AI has no Grok image module, so the body
 * construction (response_format url, aspect_ratio, resolution 1k/2k,
 * image/images dataUrl for image-to-image) is replicated verbatim.
 */
public class GrokImageClient {

    private static final Logger log = LoggerFactory.getLogger(GrokImageClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GrokImageClient(String baseUrl, String apiKey, WebClient.Builder webClientBuilder,
                           ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).defaultHeader("Authorization", "Bearer " + apiKey).build();
        this.objectMapper = objectMapper;
    }

    public record Request(String model, String prompt, String aspectRatio, String outputSize,
                          boolean stream, List<ImagePart> images) {
        public record ImagePart(String data, String mimeType, String dataUrl) {
        }
    }
    private static String getGrokResolution(String outputSize) {
        if (outputSize == null) {
            return null;
        }
        if (outputSize.equalsIgnoreCase("2K")) {
            return "2k";
        }
        if (outputSize.equalsIgnoreCase("1K")) {
            return "1k";
        }
        return null;
    }

    private static String toGrokImageDataUrl(Request.ImagePart img) {
        if (img == null) {
            return "";
        }
        if (img.dataUrl() != null && img.dataUrl().startsWith("data:")) {
            return img.dataUrl();
        }
        String mimeType = img.mimeType() == null || img.mimeType().isEmpty() ? "image/png" : img.mimeType();
        String data = img.data() == null ? "" : img.data();
        if (data.isEmpty()) {
            return "";
        }
        if (data.startsWith("data:")) {
            return data;
        }
        return "data:" + mimeType + ";base64," + data;
    }

    /** Calls grok-imagine generations/edits; returns the image payload (URL:... or b64). */
    public String generate(Request request) {
        boolean imageToImage = request.images() != null && !request.images().isEmpty();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("prompt", request.prompt());
        payload.put("response_format", "url");
        if (request.stream()) {
            payload.put("stream", true);
        }
        if (StringUtils.hasText(request.aspectRatio()) && !request.aspectRatio().equals("auto")) {
            payload.put("aspect_ratio", request.aspectRatio());
        }
        String resolution = getGrokResolution(request.outputSize());
        if (resolution != null) {
            payload.put("resolution", resolution);
        }
        if (imageToImage) {
            List<String> dataUrls = new ArrayList<>();
            for (Request.ImagePart img : request.images()) {
                String dataUrl = toGrokImageDataUrl(img);
                if (!dataUrl.isEmpty()) {
                    dataUrls.add(dataUrl);
                }
            }
            if (dataUrls.isEmpty()) {
                throw new IllegalArgumentException("参考图数据无效");
            }
            if (dataUrls.size() == 1) {
                payload.put("image", dataUrls.get(0));
            } else {
                payload.put("images", dataUrls);
            }
        }

        String endpoint = imageToImage ? "/v1/images/edits" : "/v1/images/generations";
        log.debug("[grok-image] POST {} model={} i2i={}", endpoint, request.model(), imageToImage);

        ResponseEntity<String> response = webClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .toEntity(String.class)
                .block();

        if (response == null || response.getBody() == null) {
            throw new IllegalStateException("上游返回空响应");
        }
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        return ImageResponseParser.parse(response.getBody(), contentType, response.getStatusCode().value(), objectMapper);
    }
}
