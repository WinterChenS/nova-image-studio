package com.nova.studio.imagegen;

import tools.jackson.databind.JsonNode;

import java.util.Iterator;
import java.util.Map;

/**
 * Port of the Node backend's image payload extraction logic
 * ({@code backend/server.js} {@code getImagePayloadValue} / {@code extractImagePayload}).
 *
 * <p>Searches recursively for the first image payload: {@code data[].b64_json|url|image_url},
 * then nested {@code result} / {@code response} / {@code output}. Also normalizes
 * {@code data:image/...} prefixes and {@code URL:} markers.
 */
public final class ImagePayloadExtractor {

    private ImagePayloadExtractor() {
    }

    /**
     * Extracts the raw image payload (b64 or URL marker) from an upstream JSON payload.
     *
     * @throws IllegalStateException when no image data is found
     */
    public static String extract(JsonNode data) {
        String value = findImageValue(data, 0);
        if (value == null) {
            throw new IllegalStateException("响应中无图片数据");
        }
        return normalize(value);
    }

    private static String findImageValue(JsonNode node, int depth) {
        if (node == null || node.isNull() || depth > 3) {
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = findImageValue(item, depth + 1);
                if (value != null) {
                    return value;
                }
            }
            return null;
        }
        if (!node.isObject()) {
            return null;
        }
        JsonNode data = node.get("data");
        if (data != null && data.isArray()) {
            for (JsonNode item : data) {
                if (item != null && item.isObject()) {
                    String v = imageField(item);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        String direct = imageField(node);
        if (direct != null) {
            return direct;
        }
        for (String key : new String[]{"result", "response", "output"}) {
            JsonNode nested = node.get(key);
            if (nested != null) {
                String value = findImageValue(nested, depth + 1);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String imageField(JsonNode obj) {
        for (String key : new String[]{"b64_json", "url", "image_url"}) {
            JsonNode v = obj.get(key);
            if (v != null && v.isTextual() && !v.asText().isBlank()) {
                return v.asText();
            }
        }
        return null;
    }

    private static String normalize(String imageData) {
        if (imageData == null || imageData.isEmpty()) {
            return imageData;
        }
        if (imageData.startsWith("data:image")) {
            int comma = imageData.indexOf(',');
            return comma >= 0 ? imageData.substring(comma + 1) : imageData;
        }
        if (imageData.startsWith("http://") || imageData.startsWith("https://")) {
            return "URL:" + imageData;
        }
        return imageData;
    }

    /** Parses an SSE event stream into payloads (port of {@code parseImageEventStream}). */
    public static java.util.List<JsonNode> parseEventStream(String text, tools.jackson.databind.ObjectMapper mapper) {
        java.util.List<JsonNode> payloads = new java.util.ArrayList<>();
        java.util.List<String> dataLines = new java.util.ArrayList<>();

        Runnable flush = () -> {
            if (dataLines.isEmpty()) {
                return;
            }
            String raw = String.join("\n", dataLines).trim();
            dataLines.clear();
            if (raw.isEmpty() || raw.equals("[DONE]")) {
                return;
            }
            try {
                JsonNode parsed = mapper.readTree(raw);
                if (parsed != null) {
                    payloads.add(parsed);
                }
            } catch (Exception ignored) {
                // skip malformed lines
            }
        };

        for (String rawLine : String.valueOf(text).split("\\r?\\n")) {
            String line = rawLine.stripTrailing();
            if (line.isEmpty()) {
                flush.run();
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).stripLeading());
            }
        }
        flush.run();
        return payloads;
    }

    /**
     * Extracts the final image payload from an SSE event stream: scans events in
     * reverse, skipping {@code partial} events, returning the first non-partial
     * image payload; throws with the upstream error message when the stream only
     * carries errors. Port of {@code extractImagePayloadFromEventStream}.
     */
    public static String extractFromEventStream(String text, tools.jackson.databind.ObjectMapper mapper) {
        java.util.List<JsonNode> payloads = parseEventStream(text, mapper);
        String errorMessage = null;
        for (JsonNode p : payloads) {
            String msg = errorMessageFrom(p);
            if (msg != null) {
                errorMessage = msg;
            }
        }
        for (int i = payloads.size() - 1; i >= 0; i--) {
            JsonNode p = payloads.get(i);
            if (isPartialEvent(p)) {
                continue;
            }
            try {
                return extract(p);
            } catch (IllegalStateException ignored) {
                // keep scanning earlier events
            }
        }
        if (errorMessage != null) {
            throw new IllegalStateException(errorMessage);
        }
        throw new IllegalStateException("响应中无图片数据");
    }

    private static boolean isPartialEvent(JsonNode payload) {
        JsonNode type = payload.get("type");
        if (type == null || !type.isTextual()) {
            return false;
        }
        return type.asText().toLowerCase().contains("partial");
    }

    private static String errorMessageFrom(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return null;
        }
        JsonNode error = payload.get("error");
        if (error != null) {
            if (error.isTextual()) {
                return error.asText();
            }
            if (error.isObject()) {
                JsonNode m = error.get("message");
                if (m != null && m.isTextual()) {
                    return m.asText();
                }
                JsonNode c = error.get("code");
                if (c != null && c.isTextual()) {
                    return c.asText();
                }
            }
        }
        JsonNode type = payload.get("type");
        if (type != null && type.isTextual()) {
            String t = type.asText().toLowerCase();
            if (t.equals("error") || t.equals("upstream_error")) {
                return messageFrom(payload);
            }
        }
        return null;
    }

    private static String messageFrom(JsonNode payload) {
        JsonNode message = payload.get("message");
        if (message != null && message.isTextual() && !message.asText().trim().isEmpty()) {
            return message.asText().trim();
        }
        return null;
    }
}
