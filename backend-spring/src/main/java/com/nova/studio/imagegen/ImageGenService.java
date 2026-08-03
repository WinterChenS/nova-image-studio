package com.nova.studio.imagegen;

import com.nova.studio.infra.RuntimeEnv;
import com.nova.studio.task.TaskRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Image generation protocol router (T1.3) — port of the Node backend's
 * {@code generateNovaImage} ({@code backend/server.js}). Routes by the
 * frontend-supplied {@code protocol}:
 * <ul>
 *   <li><b>openai</b> — gpt-image generations/edits via {@link PartialImagesClient};
 *       streaming {@code partial_images} attempted first when {@code NOVA_IMAGE_STREAM}
 *       is enabled, with a non-streaming fallback when the upstream rejects the
 *       stream/partial params (ADR-4, R4).</li>
 *   <li><b>google</b> — Gemini {@code generateContent} via {@link GeminiImageClient}
 *       (custom WebClient; SA 2.0.0 GA ships no google-genai-image module, ADR-5).</li>
 *   <li><b>grok</b> — grok-imagine via {@link GrokImageClient} (custom).</li>
 * </ul>
 */
@Service
public class ImageGenService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenService.class);

    private static final Pattern IMAGE_STREAM_UNSUPPORTED_PATTERN = Pattern.compile(
            "(?i)(?:(?:stream|partial_images).*(?:unsupported|not supported|unknown|unrecognized|invalid)"
                    + "|(?:unsupported|not supported|unknown|unrecognized|invalid).*(?:stream|partial_images)"
                    + "|(?:stream|partial_images).*(?:不支持|未知|无效)"
                    + "|(?:不支持|未知|无效).*(?:stream|partial_images))");

    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final RuntimeEnv runtimeEnv;
    private final int partialImagesDefault;

    public ImageGenService(WebClient.Builder webClientBuilder,
                           ObjectMapper objectMapper,
                           RuntimeEnv runtimeEnv,
                           @Value("${nova.task.image-stream-partial-images:1}") int partialImagesDefault) {
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.runtimeEnv = runtimeEnv;
        this.partialImagesDefault = partialImagesDefault;
    }

    public static String normalizeBaseUrl(String url) {
        String normalized = url == null ? "" : url.trim().replaceAll("/+$", "");
        return normalized;
    }

    /** Node normalizeProtocolBaseUrl: google strips trailing /v1beta, others strip /v1. */
    public static String normalizeProtocolBaseUrl(String protocol, String url) {
        String normalized = normalizeBaseUrl(url);
        if (normalized.isEmpty()) {
            return "";
        }
        if (("google".equals(protocol) || "google-gemini".equals(protocol))) {
            return normalized.endsWith("/v1beta") ? normalized.substring(0, normalized.length() - 7) : normalized;
        }
        return normalized.endsWith("/v1") ? normalized.substring(0, normalized.length() - 3) : normalized;
    }

    /**
     * Generates one image for the given task request. Returns the raw image
     * payload: base64 (no prefix), {@code URL:...} for remote urls, or
     * {@code MULTI_URL:...|||...} for multi-image responses (legacy upstreams).
     */
    public String generate(String protocol, String apiKey, TaskRequest request) {
        String baseUrl = normalizeProtocolBaseUrl(protocol, request.baseUrl());
        if (baseUrl.isEmpty()) {
            throw new IllegalArgumentException("缺少 API 基础地址");
        }
        if ("openai".equals(protocol)) {
            return generateOpenAi(apiKey, request, baseUrl);
        }
        if ("grok".equals(protocol)) {
            return generateGrok(apiKey, request, baseUrl);
        }
        return generateGemini(apiKey, request, baseUrl);
    }

    private String generateOpenAi(String apiKey, TaskRequest request, String baseUrl) {
        String resolvedSize = GptImageRequestResolver.resolveGptImageRequestSize(
                request.customSize(), request.model(), request.outputSize(), request.aspectRatio());
        GptImageRequestResolver.AdvancedParams advanced =
                GptImageRequestResolver.normalizeGptImageAdvancedParams(
                        request.gptImageQuality(), request.gptImageStyle(), request.gptImageBackground());

        boolean streamEnabled = runtimeEnv.getBoolean("NOVA_IMAGE_STREAM", true);
        if (!streamEnabled) {
            return requestGptImage(apiKey, request, baseUrl, resolvedSize, advanced, false);
        }
        try {
            return requestGptImage(apiKey, request, baseUrl, resolvedSize, advanced, true);
        } catch (RuntimeException e) {
            if (!isImageStreamUnsupportedError(e)) {
                throw e;
            }
            log.warn("[image-stream] 上游不支持图片流式参数，回退非流式请求: {}", e.getMessage());
            return requestGptImage(apiKey, request, baseUrl, resolvedSize, advanced, false);
        }
    }

    private String requestGptImage(String apiKey, TaskRequest request, String baseUrl, String resolvedSize,
                                   GptImageRequestResolver.AdvancedParams advanced, boolean stream) {
        Integer partialImages = stream ? Math.min(3, Math.max(0, partialImagesDefault)) : null;
        if ("image-to-image".equals(request.mode())) {
            if (request.images() == null || request.images().isEmpty()) {
                throw new IllegalArgumentException("图生图模式需要至少一张参考图");
            }
            List<PartialImagesClient.EditRequest.ImagePart> parts = request.images().stream()
                    .map(img -> new PartialImagesClient.EditRequest.ImagePart(img.data(), img.mimeType()))
                    .toList();
            PartialImagesClient client = new PartialImagesClient(baseUrl, apiKey, webClientBuilder, objectMapper);
            return client.generateEdits(new PartialImagesClient.EditRequest(
                    request.model(), request.prompt(), parts, partialImages,
                    resolvedSize, advanced.quality(), advanced.style(), advanced.background()));
        }
        PartialImagesClient client = new PartialImagesClient(baseUrl, apiKey, webClientBuilder, objectMapper);
        return client.generate(new PartialImagesClient.Request(
                request.model(), request.prompt(), 1, partialImages,
                resolvedSize, advanced.quality(), advanced.style(), advanced.background(), null, null));
    }

    private String generateGemini(String apiKey, TaskRequest request, String baseUrl) {
        List<GeminiImageClient.InlineImage> images = request.images() == null ? List.of()
                : request.images().stream()
                .map(img -> new GeminiImageClient.InlineImage(img.data(), img.mimeType()))
                .toList();
        GeminiImageClient client = new GeminiImageClient(baseUrl, apiKey, webClientBuilder, objectMapper);
        return client.generate(new GeminiImageClient.Request(
                request.model(), request.prompt(), images, request.temperature(),
                request.outputSize(), request.aspectRatio()));
    }

    private String generateGrok(String apiKey, TaskRequest request, String baseUrl) {
        if ("image-to-image".equals(request.mode()) && (request.images() == null || request.images().isEmpty())) {
            throw new IllegalArgumentException("图生图模式需要至少一张参考图");
        }
        List<GrokImageClient.Request.ImagePart> images = request.images() == null ? List.of()
                : request.images().stream()
                .map(img -> new GrokImageClient.Request.ImagePart(img.data(), img.mimeType(), null))
                .toList();
        GrokImageClient client = new GrokImageClient(baseUrl, apiKey, webClientBuilder, objectMapper);
        return client.generate(new GrokImageClient.Request(
                request.model(), request.prompt(), request.aspectRatio(), request.outputSize(), false, images));
    }

    public static boolean isImageStreamUnsupportedError(Throwable error) {
        String message = error == null ? "" : String.valueOf(error.getMessage());
        return IMAGE_STREAM_UNSUPPORTED_PATTERN.matcher(message).find();
    }
}
