package com.nova.studio.imagegen;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Locale;

/**
 * Shared upstream image response parser — port of the Node backend's
 * {@code parseGptImageResponse} / {@code getUpstreamErrorText} /
 * {@code isLikelyHtmlResponse} / {@code summarizeUnexpectedResponse}
 * ({@code backend/server.js}). Used by both the OpenAI-compatible clients and
 * the Grok client so SSE / JSON / HTML / error behavior is identical.
 */
public final class ImageResponseParser {

    private ImageResponseParser() {
    }

    public static String parse(String responseBody, String contentType, int statusCode, ObjectMapper mapper) {
        if (statusCode < 200 || statusCode >= 300) {
            String errorText = upstreamErrorText(responseBody, mapper);
            throw new IllegalStateException("API 请求失败: " + statusCode + (errorText.isEmpty() ? "" : " " + errorText));
        }
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("text/event-stream")) {
            return ImagePayloadExtractor.extractFromEventStream(responseBody, mapper);
        }
        if (isLikelyHtmlResponse(responseBody)) {
            throw new IllegalStateException("上游返回了 HTML 页面而不是 JSON。通常是 baseUrl 配置错误、请求被站点网关拦截，或该地址并非兼容的图片 API。");
        }
        JsonNode data = parseJsonSafely(responseBody, mapper);
        if (data == null) {
            String summary = summarizeUnexpectedResponse(responseBody);
            throw new IllegalStateException(summary.isEmpty() ? "响应 JSON 格式无效" : "响应 JSON 格式无效: " + summary);
        }
        String errorMessage = getErrorMessageFromPayload(data, mapper);
        if (errorMessage != null) {
            throw new IllegalStateException(errorMessage);
        }
        return ImagePayloadExtractor.extract(data);
    }

    private static String upstreamErrorText(String text, ObjectMapper mapper) {
        String trimmed = String.valueOf(text).trim();
        JsonNode data = parseJsonSafely(trimmed, mapper);
        String message = getErrorMessageFromPayload(data, mapper);
        if (message == null) {
            message = getMessageFromPayload(data, mapper);
        }
        if (message != null) {
            return message;
        }
        return trimmed.length() > 500 ? trimmed.substring(0, 500) + "…" : trimmed;
    }

    private static String getMessageFromPayload(JsonNode payload, ObjectMapper mapper) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        JsonNode message = payload.get("message");
        if (message != null && message.isTextual() && !message.asText().trim().isEmpty()) {
            return message.asText().trim();
        }
        JsonNode error = payload.get("error");
        if (error != null && error.isTextual() && !error.asText().trim().isEmpty()) {
            return error.asText().trim();
        }
        if (error != null && error.isObject()) {
            JsonNode em = error.get("message");
            if (em != null && em.isTextual() && !em.asText().trim().isEmpty()) {
                return em.asText().trim();
            }
            JsonNode code = error.get("code");
            if (code != null && code.isTextual() && !code.asText().trim().isEmpty()) {
                return code.asText().trim();
            }
        }
        return null;
    }

    private static String getErrorMessageFromPayload(JsonNode payload, ObjectMapper mapper) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        if (payload.has("error")) {
            return getMessageFromPayload(payload, mapper);
        }
        JsonNode type = payload.get("type");
        if (type != null && type.isTextual()) {
            String t = type.asText().toLowerCase(Locale.ROOT);
            if (t.equals("error") || t.equals("upstream_error")) {
                return getMessageFromPayload(payload, mapper);
            }
        }
        return null;
    }

    private static JsonNode parseJsonSafely(String text, ObjectMapper mapper) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isLikelyHtmlResponse(String text) {
        String trimmed = String.valueOf(text).trim().toLowerCase(Locale.ROOT);
        return trimmed.startsWith("<!doctype html") || trimmed.startsWith("<html")
                || trimmed.startsWith("<head") || trimmed.startsWith("<body");
    }

    private static String summarizeUnexpectedResponse(String text) {
        String trimmed = String.valueOf(text).trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (isLikelyHtmlResponse(trimmed)) {
            return "上游返回了 HTML 页面而不是 JSON。通常是 baseUrl 配置错误、请求被站点网关拦截，或该地址并非兼容的图片 API。";
        }
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "…" : trimmed;
    }
}
