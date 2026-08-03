package com.nova.studio.imagegen;

import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Custom OpenAI-compatible image client for the {@code partial_images} streaming
 * endpoint (T0.3 spike / T1.3, ADR-4). Spring AI's {@code OpenAiImageModel} is
 * blocking and has no partial/stream support, so this WebClient-based client
 * replicates the Node backend's streaming call:
 *
 * <pre>
 * POST {baseUrl}/v1/images/generations   (text-to-image, JSON)
 * POST {baseUrl}/v1/images/edits         (image-to-image, multipart)
 * {"model":..., "prompt":..., "n":1, "stream":true, "partial_images":N,
 *  "size":..., "quality":..., "output_format":"png", ...}
 * </pre>
 *
 * <p>Response handling (ported from Node {@code parseGptImageResponse} via
 * {@link ImageResponseParser}): SSE streams are parsed (partial events skipped,
 * final image extracted); any other content type is treated as JSON
 * (non-streaming fallback path). The fallback also covers upstreams that reject
 * {@code stream}/{@code partial_images} (see {@link ImageGenService}).
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

    public record EditRequest(String model, String prompt, List<ImagePart> images, Integer partialImages,
                              String size, String quality, String style, String background) {
        public record ImagePart(String data, String mimeType) {
        }
    }

    /** Calls the streaming/non-streaming generation endpoint (text-to-image). */
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

        log.debug("[openai-image] POST /v1/images/generations model={} stream={} partial={}",
                request.model(), body.containsKey("stream"), request.partialImages());

        ResponseEntity<String> response = webClient.post()
                .uri("/v1/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block();

        return parseResponse(response, "/v1/images/generations");
    }

    /** Calls the edits endpoint (image-to-image, multipart form-data). */
    public String generateEdits(EditRequest request) {
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("model", request.model());
        form.part("prompt", request.prompt());
        form.part("n", "1");
        if (request.partialImages() != null && request.partialImages() > 0) {
            form.part("stream", "true");
            form.part("partial_images", String.valueOf(request.partialImages()));
        }
        if (StringUtils.hasText(request.quality())) {
            form.part("quality", request.quality());
        }
        if (StringUtils.hasText(request.background())) {
            form.part("background", request.background());
        }
        form.part("output_format", "png");
        if (request.style() != null && (request.style().equals("vivid") || request.style().equals("natural"))) {
            form.part("style", request.style());
        }
        if (StringUtils.hasText(request.size())) {
            form.part("size", request.size());
        }
        for (int i = 0; i < request.images().size(); i++) {
            EditRequest.ImagePart img = request.images().get(i);
            String mimeType = img.mimeType() == null || img.mimeType().isEmpty() ? "image/png" : img.mimeType();
            String extension = mimeType.contains("/") ? mimeType.split("/")[1] : "png";
            byte[] bytes = java.util.Base64.getDecoder().decode(img.data());
            final int idx = i;
            form.part("image", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return "image-" + idx + "." + extension;
                }
            }, MediaType.parseMediaType(mimeType));
        }

        log.debug("[openai-image] POST /v1/images/edits model={} images={} partial={}",
                request.model(), request.images().size(), request.partialImages());

        ResponseEntity<String> response = webClient.post()
                .uri("/v1/images/edits")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(form.build()))
                .retrieve()
                .toEntity(String.class)
                .block();

        return parseResponse(response, "/v1/images/edits");
    }

    private String parseResponse(ResponseEntity<String> response, String endpoint) {
        if (response == null || response.getBody() == null) {
            throw new IllegalStateException("上游返回空响应");
        }
        String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
        String result = ImageResponseParser.parse(response.getBody(), contentType,
                response.getStatusCode().value(), objectMapper);
        log.debug("[openai-image] {} -> {} bytes payload", endpoint, result.length());
        return result;
    }
}
