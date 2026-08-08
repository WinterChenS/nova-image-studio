package com.nova.studio.agent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WIN-39 (WIN-41 T10) — 统一 Agent 事件转译器：把 4 个上游文本协议的 SSE 数据行
 * 转译为统一事件流（ARCH Part G.3：delta / reasoning / proposal / tool / retry /
 * error / done / ping），语义基准 = 前端 {@code agent-chat-client.ts} 的
 * {@code handleAgentStreamEvent} + {@code parseProposalArguments}（对拍基准，
 * T10 黄金样例集）。
 *
 * <p>协议：openai-responses（默认）/ openai-chat-completions / anthropic-messages /
 * google-gemini。每个数据行经 {@link #handle} 处理，产出 0..n 个统一事件；
 * 协议内错误事件抛 {@link UpstreamError}，由调用方（AgentChatService）分类重试。
 */
public class AgentStreamTranslator {

    /** 统一事件名（ARCH Part G.3）。 */
    public static final String EV_DELTA = "delta";
    public static final String EV_REASONING = "reasoning";
    public static final String EV_PROPOSAL = "proposal";
    public static final String EV_TOOL = "tool";
    public static final String EV_RETRY = "retry";
    public static final String EV_ERROR = "error";
    public static final String EV_DONE = "done";
    public static final String EV_PING = "ping";

    public static final String TOOL_NAME = "propose_image_action";

    private final ObjectMapper objectMapper;

    public AgentStreamTranslator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 单次流式会话的转译状态（文本/tool 参数累积）。 */
    public static final class StreamState {
        public final StringBuilder accumulated = new StringBuilder();
        public String toolArgs = "";
        public final Map<Integer, String> toolArgsByIndex = new HashMap<>();
        public boolean done = false;
    }

    /** 统一事件接收器。 */
    public interface Sink {
        void event(String type, Map<String, Object> data) throws IOException;
    }

    /** 上游协议错误（镜像前端「模型返回错误」抛出语义）。 */
    public static final class UpstreamError extends RuntimeException {
        public UpstreamError(String message) {
            super(message);
        }
    }

    /**
     * 处理一个 SSE 数据行（已解码为 JSON 字符串 + 原始 SSE event 名）。
     *
     * @throws UpstreamError  协议错误事件（由调用方分类重试）
     * @throws IOException    事件写出失败
     */
    public void handle(String protocol, String rawEventType, String dataJson,
                       StreamState state, Sink sink) throws IOException {
        if (dataJson == null || dataJson.isBlank()) {
            return;
        }
        if ("[DONE]".equals(dataJson.trim())) {
            return;   // 由调用方在流结束统一 emit done
        }
        JsonNode payload;
        try {
            payload = objectMapper.readTree(dataJson);
        } catch (Exception e) {
            return;   // 非 JSON 行忽略（与前端一致）
        }
        if (payload == null || !payload.isObject()) {
            return;
        }
        switch (protocol == null ? "" : protocol) {
            case "openai-chat-completions" -> handleChatCompletions(rawEventType, payload, state, sink);
            case "anthropic-messages" -> handleAnthropic(rawEventType, payload, state, sink);
            case "google-gemini" -> handleGemini(payload, state, sink);
            default -> handleResponses(rawEventType, payload, state, sink);
        }
    }

    // ===== openai-chat-completions =====

    private void handleChatCompletions(String rawEventType, JsonNode payload,
                                       StreamState state, Sink sink) throws IOException {
        JsonNode error = payload.get("error");
        if ("error".equals(rawEventType) || (error != null && error.hasNonNull("message"))) {
            throw new UpstreamError(text(error, "message", text(payload, "message", "模型返回错误")));
        }
        JsonNode choice = payload.path("choices").path(0);
        if (choice.isMissingNode() || choice.isNull()) {
            return;
        }
        JsonNode delta = choice.get("delta");
        if (delta != null && delta.isObject()) {
            String reasoningDelta = firstText(delta, "reasoning_content", "reasoning", "reasoning_text");
            if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                sink.event(EV_REASONING, Map.of("text", reasoningDelta));
            }
            JsonNode content = delta.get("content");
            String textDelta = contentText(content);
            if (textDelta != null && !textDelta.isEmpty()) {
                state.accumulated.append(textDelta);
                sink.event(EV_DELTA, Map.of("text", textDelta));
            }
            JsonNode toolCalls = delta.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").isNumber() ? tc.path("index").asInt() : 0;
                    String fragment = tc.path("function").path("arguments").asText("");
                    if (fragment.isEmpty()) {
                        continue;
                    }
                    String next = state.toolArgsByIndex.getOrDefault(index, "") + fragment;
                    state.toolArgsByIndex.put(index, next);
                    state.toolArgs = next;
                }
            }
        }
        JsonNode message = choice.get("message");
        if (message != null && message.isObject()) {
            String reasoningFull = firstText(message, "reasoning_content", "reasoning", "reasoning_text");
            if (reasoningFull != null && !reasoningFull.isEmpty()) {
                sink.event(EV_REASONING, Map.of("text", reasoningFull));
            }
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    String fullArgs = tc.path("function").path("arguments").asText("");
                    if (!fullArgs.isEmpty()) {
                        state.toolArgs = fullArgs;
                    }
                }
            }
        }
    }

    // ===== anthropic-messages =====

    private void handleAnthropic(String rawEventType, JsonNode payload,
                                 StreamState state, Sink sink) throws IOException {
        String eventType = text(payload, "type", rawEventType == null ? "" : rawEventType);
        switch (eventType == null ? "" : eventType) {
            case "content_block_start" -> {
                JsonNode block = payload.get("content_block");
                if (block == null || !block.isObject()) {
                    return;
                }
                if ("text".equals(block.path("type").asText()) && block.hasNonNull("text")) {
                    String t = block.get("text").asText();
                    state.accumulated.append(t);
                    sink.event(EV_DELTA, Map.of("text", t));
                }
                if ("thinking".equals(block.path("type").asText())
                        && block.hasNonNull("text") && !block.get("text").asText().isEmpty()) {
                    sink.event(EV_REASONING, Map.of("text", block.get("text").asText()));
                }
                if ("tool_use".equals(block.path("type").asText())
                        && block.has("input") && payload.path("index").isNumber()) {
                    String serialized = block.get("input").toString();
                    int index = payload.path("index").asInt();
                    state.toolArgsByIndex.put(index, serialized);
                    state.toolArgs = serialized;
                }
            }
            case "content_block_delta" -> {
                JsonNode delta = payload.get("delta");
                if (delta == null || !delta.isObject()) {
                    return;
                }
                if ("thinking_delta".equals(delta.path("type").asText()) && delta.hasNonNull("thinking")) {
                    sink.event(EV_REASONING, Map.of("text", delta.get("thinking").asText()));
                }
                if (delta.hasNonNull("text")) {
                    String t = delta.get("text").asText();
                    state.accumulated.append(t);
                    sink.event(EV_DELTA, Map.of("text", t));
                }
                if (delta.hasNonNull("partial_json")) {
                    int index = payload.path("index").isNumber() ? payload.path("index").asInt() : 0;
                    String next = state.toolArgsByIndex.getOrDefault(index, "") + delta.get("partial_json").asText();
                    state.toolArgsByIndex.put(index, next);
                    state.toolArgs = next;
                }
            }
            case "message_stop" -> {
                JsonNode message = payload.get("message");
                if (message != null && message.has("content") && message.get("content").isArray()) {
                    for (JsonNode part : message.get("content")) {
                        if ("tool_use".equals(part.path("type").asText()) && part.has("input")
                                && state.toolArgs.trim().isEmpty()) {
                            state.toolArgs = part.get("input").toString();
                        }
                    }
                }
                state.done = true;
            }
            case "error" -> throw new UpstreamError(
                    text(payload.path("error"), "message", text(payload, "message", "模型返回错误")));
            default -> {
                // 其他事件忽略
            }
        }
    }

    // ===== google-gemini =====

    private void handleGemini(JsonNode payload, StreamState state, Sink sink) throws IOException {
        JsonNode error = payload.get("error");
        if (error != null && error.hasNonNull("message")) {
            throw new UpstreamError(error.get("message").asText());
        }
        JsonNode candidates = payload.get("candidates");
        if (candidates == null || !candidates.isArray()) {
            return;
        }
        for (JsonNode candidate : candidates) {
            JsonNode parts = candidate.path("content").path("parts");
            if (!parts.isArray()) {
                continue;
            }
            for (JsonNode part : parts) {
                if (part.hasNonNull("text") && !part.get("text").asText().isEmpty()) {
                    String t = part.get("text").asText();
                    if (part.path("thought").asBoolean(false)) {
                        sink.event(EV_REASONING, Map.of("text", t));
                    } else {
                        state.accumulated.append(t);
                        sink.event(EV_DELTA, Map.of("text", t));
                    }
                }
                JsonNode fn = part.get("functionCall");
                if (fn != null && fn.isObject() && TOOL_NAME.equals(fn.path("name").asText()) && fn.has("args")) {
                    state.toolArgs = fn.get("args").toString();
                }
            }
        }
    }

    // ===== openai-responses（默认）=====

    private void handleResponses(String rawEventType, JsonNode payload,
                                 StreamState state, Sink sink) throws IOException {
        String eventType = text(payload, "type", rawEventType == null ? "" : rawEventType);
        switch (eventType == null ? "" : eventType) {
            case "response.reasoning_summary_text.delta" -> {
                if (payload.hasNonNull("delta")) {
                    sink.event(EV_REASONING, Map.of("text", payload.get("delta").asText()));
                }
            }
            case "response.reasoning_summary_part.added" -> sink.event(EV_REASONING, Map.of("text", "\n"));
            case "response.output_text.delta" -> {
                if (payload.hasNonNull("delta")) {
                    String d = payload.get("delta").asText();
                    state.accumulated.append(d);
                    sink.event(EV_DELTA, Map.of("text", d));
                }
            }
            case "response.output_text.done" -> {
                if (payload.hasNonNull("text")) {
                    String full = payload.get("text").asText();
                    if (full.length() > state.accumulated.length()) {
                        String tail = full.substring(state.accumulated.length());
                        state.accumulated.setLength(0);
                        state.accumulated.append(full);
                        sink.event(EV_DELTA, Map.of("text", tail));
                    }
                }
            }
            case "response.function_call_arguments.delta" -> {
                if (payload.hasNonNull("delta")) {
                    state.toolArgs = state.toolArgs + payload.get("delta").asText();
                }
            }
            case "response.function_call_arguments.done" -> {
                if (payload.hasNonNull("arguments")) {
                    state.toolArgs = payload.get("arguments").asText();
                }
            }
            case "response.output_item.done" -> {
                JsonNode item = payload.get("item");
                if (item != null && "function_call".equals(item.path("type").asText())
                        && item.hasNonNull("arguments")) {
                    state.toolArgs = item.get("arguments").asText();
                }
            }
            case "response.completed" -> {
                JsonNode response = payload.get("response");
                if (response != null && response.isObject()) {
                    String fullText = response.path("output_text").asText("");
                    if (fullText.length() > state.accumulated.length()) {
                        String tail = fullText.substring(state.accumulated.length());
                        state.accumulated.setLength(0);
                        state.accumulated.append(fullText);
                        sink.event(EV_DELTA, Map.of("text", tail));
                    }
                    JsonNode output = response.get("output");
                    if (output != null && output.isArray()) {
                        for (JsonNode item : output) {
                            if ("function_call".equals(item.path("type").asText())
                                    && item.hasNonNull("arguments")
                                    && state.toolArgs.trim().isEmpty()) {
                                state.toolArgs = item.get("arguments").asText();
                            }
                        }
                    }
                }
                state.done = true;
            }
            case "error", "response.error" -> throw new UpstreamError(
                    text(payload.path("error"), "message", text(payload, "message", "模型返回错误")));
            default -> {
                // 其他事件忽略
            }
        }
    }

    // ===== 提案解析（镜像 parseProposalArguments）=====

    /**
     * 解析累积的 tool 参数为统一 proposal 事件数据（与前端 AgentProposal 字段一致，
     * 前端可直接 setProposal）；非法/空 prompt → null。
     */
    public Map<String, Object> parseProposal(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
        if (parsed == null || !parsed.isObject()) {
            return null;
        }
        String action = "generate".equals(parsed.path("action").asText()) ? "generate" : "edit";
        String prompt = parsed.path("prompt").asText("");
        if (prompt.trim().isEmpty()) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("action", action);
        data.put("prompt", prompt);
        data.put("reason", parsed.path("reason").asText(""));
        List<String> ids = new ArrayList<>();
        JsonNode refs = parsed.get("referenced_image_ids");
        if (refs != null && refs.isArray()) {
            refs.forEach(n -> {
                if (n.isTextual()) {
                    ids.add(n.asText());
                }
            });
        }
        data.put("referencedImageIds", ids);
        putTextIfPresent(data, "requestedAspectRatio", parsed, "requested_aspect_ratio");
        putTextIfPresent(data, "suggestedAspectRatio", parsed, "suggested_aspect_ratio");
        putTextIfPresent(data, "requestedOutputSize", parsed, "requested_output_size");
        putNumberIfPresent(data, "temperature", parsed, "temperature");
        putNumberIfPresent(data, "parallelCount", parsed, "parallel_count");
        putTextIfPresent(data, "gptImageQuality", parsed, "gpt_image_quality");
        putTextIfPresent(data, "gptImageStyle", parsed, "gpt_image_style");
        putTextIfPresent(data, "gptImageBackground", parsed, "gpt_image_background");
        putTextIfPresent(data, "requestedModelId", parsed, "requested_model_id");
        return data;
    }

    // ===== helpers =====

    private static void putTextIfPresent(Map<String, Object> data, String key, JsonNode node, String field) {
        if (node.hasNonNull(field)) {
            String value = node.get(field).asText().trim();
            if (!value.isEmpty()) {
                data.put(key, value);
            }
        }
    }

    private static void putNumberIfPresent(Map<String, Object> data, String key, JsonNode node, String field) {
        if (node.hasNonNull(field) && node.get(field).isNumber()) {
            data.put(key, node.get(field).asDouble());
        }
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.hasNonNull(field) && !node.get(field).asText().isEmpty()) {
                return node.get(field).asText();
            }
        }
        return null;
    }

    /** content 字段：string 或 [{type:text,text}] 数组合并。 */
    private static String contentText(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText()) && part.hasNonNull("text")) {
                    sb.append(part.get("text").asText());
                }
            }
            return sb.toString();
        }
        return null;
    }

    private static String text(JsonNode node, String field, String fallback) {
        if (node != null && node.hasNonNull(field)) {
            return node.get(field).asText();
        }
        return fallback;
    }

    /** 构造统一事件 data（便捷方法）。 */
    public static Map<String, Object> data(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(key, value);
        return map;
    }

    public static ObjectNode json(ObjectMapper mapper) {
        return mapper.createObjectNode();
    }
}
