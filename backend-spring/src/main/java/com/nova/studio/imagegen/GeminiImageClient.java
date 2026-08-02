package com.nova.studio.imagegen;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gemini image client — custom WebClient implementation of the Google
 * {@code generateContent} image protocol (T0.2/T0.3 spike).
 *
 * <p><b>Spike finding:</b> released Spring AI 2.0.0 GA on Maven Central ships
 * <b>no</b> {@code GoogleGenAiImageModel} / {@code spring-ai-google-genai-image}
 * module (verified against the actual jars) — the ARCH §B.4/ADR-5 claim was based
 * on the {@code main} branch which is ahead of the 2.0.0 release train. Therefore
 * the Gemini image path must be implemented as a custom REST client, replicating
 * the Node backend's {@code generateNovaGeminiImage}:
 *
 * <pre>
 * POST {baseUrl}/v1beta/models/{model}:generateContent
 * x-goog-api-key: {apiKey}
 * {"contents":[{"role":"user","parts":[{"text":...},{"inlineData":{"data":...,"mimeType":...}}]}],
 *  "generationConfig":{"temperature":...,"responseModalities":["IMAGE"],
 *                      "imageConfig":{"imageSize":...,"aspectRatio":...}}}
 * </pre>
 *
 * <p>Response extraction: {@code candidates[0].content.parts[].inlineData.data}
 * (also accepts {@code inline_data}).
 */
public class GeminiImageClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiImageClient.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public GeminiImageClient(String baseUrl, String apiKey, WebClient.Builder webClientBuilder,
                             ObjectMapper objectMapper) {
        this.webClient = webClientBuilder.baseUrl(baseUrl)
                .defaultHeader("x-goog-api-key", apiKey)
                .build();
        this.objectMapper = objectMapper;
    }

    public record Request(String model, String prompt, List<InlineImage> images,
                          Double temperature, String imageSize, String aspectRatio) {
    }

    public record InlineImage(String data, String mimeType) {
    }

    /** Calls {@code generateContent} and returns the base64 image payload. */
    public String generate(Request request) {
        List<Map<String, Object>> parts = new ArrayList<>();
        Map<String, Object> textPart = new LinkedHashMap<>();
        textPart.put("text", request.prompt());
        parts.add(textPart);
        for (InlineImage img : request.images()) {
            Map<String, Object> inline = new LinkedHashMap<>();
            inline.put("inlineData", Map.of("data", img.data(), "mimeType", img.mimeType()));
            parts.add(inline);
        }

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        if (request.temperature() != null) {
            generationConfig.put("temperature", request.temperature());
        }
        generationConfig.put("responseModalities", List.of("IMAGE"));
        Map<String, Object> imageConfig = new LinkedHashMap<>();
        if (StringUtils.hasText(request.imageSize())) {
            imageConfig.put("imageSize", request.imageSize());
        }
        if (StringUtils.hasText(request.aspectRatio())) {
            imageConfig.put("aspectRatio", request.aspectRatio());
        }
        generationConfig.put("imageConfig", imageConfig);

        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", parts)),
                "generationConfig", generationConfig);

        log.debug("[gemini-image] POST /v1beta/models/{}/:generateContent imageSize={} aspectRatio={}",
                request.model(), request.imageSize(), request.aspectRatio());

        var response = webClient.post()
                .uri("/v1beta/models/{model}:generateContent", request.model())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toEntity(String.class)
                .block();

        if (response == null || response.getBody() == null) {
            throw new IllegalStateException("上游返回空响应");
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new IllegalStateException("API 请求失败: " + response.getStatusCode().value() + " " + response.getBody());
        }
        try {
            JsonNode json = objectMapper.readTree(response.getBody());
            return extractInlineData(json);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("响应 JSON 格式无效", e);
        }
    }

    private String extractInlineData(JsonNode json) {
        JsonNode candidates = json.get("candidates");
        if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
            JsonNode content = candidates.get(0).get("content");
            JsonNode parts = content != null ? content.get("parts") : null;
            if (parts != null && parts.isArray()) {
                for (JsonNode part : parts) {
                    JsonNode inline = part.get("inlineData");
                    if (inline == null) {
                        inline = part.get("inline_data");
                    }
                    JsonNode data = inline != null ? inline.get("data") : null;
                    if (data != null && data.isTextual() && !data.asText().isBlank()) {
                        return data.asText();
                    }
                }
            }
        }
        throw new IllegalStateException("响应中无图片数据");
    }
}
