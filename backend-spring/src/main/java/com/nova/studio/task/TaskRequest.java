package com.nova.studio.task;

import java.util.ArrayList;
import java.util.List;

/**
 * Parsed task request — mirrors the Node backend's {@code requestForDb} shape
 * plus the full reference images kept in memory ({@code taskRefImages}).
 * Built from either the create-task body (full images incl. base64 data) or the
 * stored {@code request_json} (images carry only mimeType; the data is
 * re-attached from the runtime image map before generation).
 */
public record TaskRequest(
        String mode,
        String protocol,
        String baseUrl,
        String prompt,
        String outputSize,
        String customSize,
        String aspectRatio,
        Double temperature,
        String model,
        String gptImageQuality,
        String gptImageStyle,
        String gptImageBackground,
        int parallelCount,
        List<ImageReference> images) {

    public record ImageReference(String data, String mimeType) {
    }

    public static TaskRequest fromStored(tools.jackson.databind.JsonNode node, List<ImageReference> refImages) {
        String mode = text(node, "mode");
        String protocol = text(node, "protocol");
        String baseUrl = text(node, "baseUrl");
        String prompt = text(node, "prompt");
        String outputSize = text(node, "outputSize");
        String customSize = text(node, "customSize");
        String aspectRatio = text(node, "aspectRatio");
        Double temperature = number(node, "temperature");
        String model = text(node, "model");
        String gptImageQuality = text(node, "gptImageQuality");
        String gptImageStyle = text(node, "gptImageStyle");
        String gptImageBackground = text(node, "gptImageBackground");
        int parallelCount = node.has("parallelCount") ? node.get("parallelCount").asInt(1) : 1;

        List<ImageReference> images = new ArrayList<>();
        if (refImages != null) {
            images.addAll(refImages);
        } else {
            tools.jackson.databind.JsonNode imagesNode = node.get("images");
            if (imagesNode != null && imagesNode.isArray()) {
                for (tools.jackson.databind.JsonNode img : imagesNode) {
                    images.add(new ImageReference(text(img, "data"), text(img, "mimeType")));
                }
            }
        }
        return new TaskRequest(mode, protocol, baseUrl, prompt, outputSize, customSize, aspectRatio,
                temperature, model, gptImageQuality, gptImageStyle, gptImageBackground, parallelCount, images);
    }

    private static String text(tools.jackson.databind.JsonNode node, String key) {
        tools.jackson.databind.JsonNode value = node.get(key);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Double number(tools.jackson.databind.JsonNode node, String key) {
        tools.jackson.databind.JsonNode value = node.get(key);
        return value != null && value.isNumber() ? value.asDouble() : null;
    }
}
