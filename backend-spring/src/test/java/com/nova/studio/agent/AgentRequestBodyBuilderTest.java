package com.nova.studio.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WIN-41 (T9/T10) — 4 协议请求体构造（镜像 agent-chat-client.ts buildAgentRequestBody
 * 语义：system 指令注入、历史过滤、工具 schema、联网搜索、压缩摘要注入）。
 */
class AgentRequestBodyBuilderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentRequestBodyBuilder builder = new AgentRequestBodyBuilder(mapper);

    private final List<AgentRequestBodyBuilder.HistoryTurn> history = List.of(
            new AgentRequestBodyBuilder.HistoryTurn("user", "帮我画一只猫"),
            new AgentRequestBodyBuilder.HistoryTurn("assistant", "好的"),
            new AgentRequestBodyBuilder.HistoryTurn("system-note", "已取消"),   // 应被过滤
            new AgentRequestBodyBuilder.HistoryTurn("context-divider", "分隔"),  // 应被过滤
            new AgentRequestBodyBuilder.HistoryTurn("user", "  ")                // 空白应被过滤
    );

    @Test
    void instructionsContainCatalogAndModelCatalog() {
        String instructions = builder.buildInstructions(
                List.of(new AgentRequestBodyBuilder.CatalogEntry("img_1", "一只橘猫")),
                List.of(new AgentRequestBodyBuilder.ModelEntry("banana-pro", "Banana Pro", "4K")));
        assertThat(instructions).contains("[img_1] 一只橘猫");
        assertThat(instructions).contains("- id: banana-pro, 名称: \"Banana Pro\", 最大分辨率: 4K");
        assertThat(instructions).contains("propose_image_action");
    }

    @Test
    void instructionsEmptyCatalogHasEmptyHint() {
        String instructions = builder.buildInstructions(List.of(), List.of());
        assertThat(instructions).contains("当前可用图片目录：（空，还没有任何图片）");
        assertThat(instructions).contains("当前可用图像模型：（空，请在设置中配置）");
    }

    @Test
    void responsesBodyMirrorsFrontendShape() throws Exception {
        JsonNode body = builder.buildRequestBody("openai-responses", "gpt-5.4-mini", history,
                "instructions", false, null);
        assertThat(body.path("model").asText()).isEqualTo("gpt-5.4-mini");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("instructions").asText()).isEqualTo("instructions");
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("medium");
        assertThat(body.path("tool_choice").asText()).isEqualTo("auto");
        JsonNode input = body.path("input");
        assertThat(input.isArray()).isTrue();
        assertThat(input.size()).isEqualTo(2);   // system-note/divider/空白 已过滤
        assertThat(input.get(0).path("role").asText()).isEqualTo("user");
        assertThat(input.get(0).path("content").get(0).path("type").asText()).isEqualTo("input_text");
        assertThat(input.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(input.get(1).path("content").get(0).path("type").asText()).isEqualTo("output_text");
        JsonNode tools = body.path("tools");
        assertThat(tools.size()).isEqualTo(1);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("propose_image_action");
    }

    @Test
    void chatCompletionsBodyHasSystemInstructionAndToolSchema() throws Exception {
        JsonNode body = builder.buildRequestBody("openai-chat-completions", "gpt-4o-mini", history,
                "instructions", false, null);
        JsonNode messages = body.path("messages");
        assertThat(messages.size()).isEqualTo(3);   // system + 2 条真实历史
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo("instructions");
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("high");
        assertThat(body.path("tools").get(0).path("function").path("name").asText())
                .isEqualTo("propose_image_action");
        assertThat(body.path("tools").get(0).path("function").path("parameters").has("properties")).isTrue();
    }

    @Test
    void anthropicBodyHasThinkingAndWebSearchToolWhenEnabled() throws Exception {
        JsonNode body = builder.buildRequestBody("anthropic-messages", "claude-sonnet-4-20250514",
                history, "instructions", true, null);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("system").asText()).isEqualTo("instructions");
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("adaptive");
        JsonNode tools = body.path("tools");
        assertThat(tools.size()).isEqualTo(2);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("propose_image_action");
        assertThat(tools.get(1).path("type").asText()).isEqualTo("web_search_20250305");
    }

    @Test
    void geminiBodyHasInstructionAsFirstUserPart() throws Exception {
        JsonNode body = builder.buildRequestBody("google-gemini", "gemini-2.5-flash", history,
                "instructions", true, null);
        JsonNode contents = body.path("contents");
        assertThat(contents.size()).isEqualTo(3);
        assertThat(contents.get(0).path("parts").get(0).path("text").asText()).isEqualTo("instructions");
        assertThat(contents.get(1).path("role").asText()).isEqualTo("user");
        assertThat(contents.get(2).path("role").asText()).isEqualTo("model");
        assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingBudget").asInt()).isEqualTo(-1);
        assertThat(body.path("tools").get(1).has("google_search")).isTrue();
    }

    @Test
    void contextSummaryInjectedAsFirstUserTurn() throws Exception {
        JsonNode body = builder.buildRequestBody("openai-responses", "m", history,
                "instructions", false, "已压缩 40 条较早消息");
        JsonNode input = body.path("input");
        assertThat(input.size()).isEqualTo(3);
        assertThat(input.get(0).path("content").get(0).path("text").asText())
                .contains("已压缩 40 条较早消息");
    }

    @Test
    void describeBodyCarriesImageDataUrlPerProtocol() throws Exception {
        String dataUrl = "data:image/png;base64,AAAA";
        JsonNode chat = builder.buildDescribeRequestBody("openai-chat-completions", "m", dataUrl);
        assertThat(chat.path("messages").get(0).path("content").get(1).path("image_url").path("url").asText())
                .isEqualTo(dataUrl);
        JsonNode anthropic = builder.buildDescribeRequestBody("anthropic-messages", "m", dataUrl);
        assertThat(anthropic.path("messages").get(0).path("content").get(1).path("source").path("type").asText())
                .isEqualTo("base64");
        assertThat(anthropic.path("messages").get(0).path("content").get(1).path("source").path("data").asText())
                .isEqualTo("AAAA");
        JsonNode gemini = builder.buildDescribeRequestBody("google-gemini", "m", dataUrl);
        assertThat(gemini.path("contents").get(0).path("parts").get(1).path("inline_data").path("mime_type").asText())
                .isEqualTo("image/png");
    }

    @Test
    void extractTextOutputPerProtocol() throws Exception {
        assertThat(builder.extractTextOutput("openai-chat-completions",
                mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}")))
                .isEqualTo("hi");
        assertThat(builder.extractTextOutput("anthropic-messages",
                mapper.readTree("{\"content\":[{\"type\":\"text\",\"text\":\"hello\"}]}")))
                .isEqualTo("hello");
        assertThat(builder.extractTextOutput("google-gemini",
                mapper.readTree("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"ni\"}]}}]}")))
                .isEqualTo("ni");
        assertThat(builder.extractTextOutput("openai-responses",
                mapper.readTree("{\"output_text\":\"responses-ok\"}")))
                .isEqualTo("responses-ok");
    }

    @Test
    void splitDataUrlHandlesPlainBase64() {
        String[] parts = AgentRequestBodyBuilder.splitDataUrl("data:image/jpeg;base64,abc=");
        assertThat(parts[0]).isEqualTo("image/jpeg");
        assertThat(parts[1]).isEqualTo("abc=");
    }
}
