package com.nova.studio.audit;

import tools.jackson.databind.JsonNode;

/**
 * T8 (WIN-28) — usage token extraction from upstream response JSON (F.3/Q5).
 * Protocol differences:
 * <ul>
 *   <li>openai-chat-completions → {@code usage.prompt_tokens/completion_tokens};</li>
 *   <li>openai-responses / anthropic-messages → {@code usage.input_tokens/output_tokens};</li>
 *   <li>google-gemini → {@code usageMetadata.promptTokenCount/candidatesTokenCount}.</li>
 * </ul>
 * Missing/malformed → null (H6: usage=null 不阻断业务).
 */
public final class UsageParser {

    private UsageParser() {
    }

    /** Parsed token counts (either side may be null when the upstream omits it). */
    public record UsageTokens(Long inputTokens, Long outputTokens) {
    }

    public static UsageTokens parseUsage(JsonNode node) {
        if (node == null) {
            return null;
        }
        JsonNode usage = node.get("usage");
        if (usage != null && usage.isObject()) {
            Long in = longOrNull(usage, "prompt_tokens", "input_tokens");
            Long out = longOrNull(usage, "completion_tokens", "output_tokens");
            if (in == null && out == null) {
                return null;
            }
            return new UsageTokens(in, out);
        }
        JsonNode metadata = node.get("usageMetadata");
        if (metadata != null && metadata.isObject()) {
            Long in = longOrNull(metadata, "promptTokenCount", "input_tokens");
            Long out = longOrNull(metadata, "candidatesTokenCount", "output_tokens");
            if (in == null && out == null) {
                return null;
            }
            return new UsageTokens(in, out);
        }
        return null;
    }

    private static Long longOrNull(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
        }
        return null;
    }
}
