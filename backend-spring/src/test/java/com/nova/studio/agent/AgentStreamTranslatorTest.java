package com.nova.studio.agent;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WIN-41 (T10) — 4 协议 × 典型流黄金样例集：统一事件转译语义与前端
 * {@code agent-chat-client.ts}（handleAgentStreamEvent + parseProposalArguments）
 * 等价（对拍基准）。样例直接取自前端单测/真实上游响应形态。
 */
class AgentStreamTranslatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentStreamTranslator translator = new AgentStreamTranslator(mapper);

    /** 收集事件的 sink。 */
    private static final class Recorder implements AgentStreamTranslator.Sink {
        final List<String> types = new ArrayList<>();
        final List<Map<String, Object>> datas = new ArrayList<>();

        @Override
        public void event(String type, Map<String, Object> data) {
            types.add(type);
            datas.add(data);
        }
    }

    private Recorder handle(String protocol, List<Map.Entry<String, String>> lines) throws IOException {
        AgentStreamTranslator.StreamState state = new AgentStreamTranslator.StreamState();
        Recorder recorder = new Recorder();
        for (Map.Entry<String, String> line : lines) {
            translator.handle(protocol, line.getKey(), line.getValue(), state, recorder);
        }
        return recorder;
    }

    // ===== openai-responses（默认）=====

    @Test
    void responsesProtocolTranslatesReasoningDeltaAndProposal() throws Exception {
        var lines = List.of(
                Map.entry("response.reasoning_summary_text.delta",
                        "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"思考中\"}"),
                Map.entry("response.output_text.delta",
                        "{\"type\":\"response.output_text.delta\",\"delta\":\"你好\"}"),
                Map.entry("response.function_call_arguments.delta",
                        "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\\\"action\\\":\\\"generate\\\",\\\"prompt\\\":\\\"一只橘猫\\\",\\\"referenced_image_ids\\\":[],\\\"reason\\\":\\\"生成新图\\\",\\\"requested_aspect_ratio\\\":null,\\\"suggested_aspect_ratio\\\":\\\"1:1\\\"\"}"),
                Map.entry("response.function_call_arguments.delta",
                        "{\"type\":\"response.function_call_arguments.delta\",\"delta\":\",\\\"parallel_count\\\":2}\"}"),
                Map.entry("response.completed",
                        "{\"type\":\"response.completed\",\"response\":{\"output_text\":\"你好\",\"output\":[{\"type\":\"function_call\",\"name\":\"propose_image_action\",\"arguments\":\"{\\\"action\\\":\\\"generate\\\",\\\"prompt\\\":\\\"一只橘猫\\\",\\\"referenced_image_ids\\\":[],\\\"reason\\\":\\\"生成新图\\\",\\\"requested_aspect_ratio\\\":null,\\\"suggested_aspect_ratio\\\":\\\"1:1\\\",\\\"parallel_count\\\":2}\"}]}}"));
        var rec = handle("openai-responses", lines);
        // function_call_arguments.delta 仅累积 tool 参数，不产出事件（与前端一致）
        assertThat(rec.types).containsExactly("reasoning", "delta");
        assertThat(rec.datas.get(0)).containsEntry("text", "思考中");
        assertThat(rec.datas.get(1)).containsEntry("text", "你好");
        assertThat(rec.types).containsExactly("reasoning", "delta");
    }

    @Test
    void responsesCompletedFiresTailDeltaAndAccumulatesToolArgs() throws Exception {
        var lines = List.of(
                Map.entry("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"abc\"}"),
                Map.entry("response.output_text.done", "{\"type\":\"response.output_text.done\",\"text\":\"abcdef\"}"),
                Map.entry("response.completed", "{\"type\":\"response.completed\",\"response\":{\"output_text\":\"abcdef\"}}"));
        AgentStreamTranslator.StreamState state = new AgentStreamTranslator.StreamState();
        Recorder rec = new Recorder();
        for (var line : lines) {
            translator.handle("openai-responses", line.getKey(), line.getValue(), state, rec);
        }
        assertThat(state.accumulated.toString()).isEqualTo("abcdef");
        // delta: abc + tail def；completed 时 output_text 与累积一致 → 无多余事件
        assertThat(rec.types).containsExactly("delta", "delta");
        assertThat(rec.datas.get(1)).containsEntry("text", "def");
    }

    @Test
    void responsesErrorEventThrowsUpstreamError() {
        var lines = List.of(
                Map.entry("response.error", "{\"type\":\"response.error\",\"error\":{\"message\":\"上游出错\"}}"));
        assertThatThrownBy(() -> handle("openai-responses", lines))
                .isInstanceOf(AgentStreamTranslator.UpstreamError.class)
                .hasMessageContaining("上游出错");
    }

    // ===== openai-chat-completions =====

    @Test
    void chatCompletionsTranslatesReasoningContentAndToolCalls() throws Exception {
        var lines = List.of(
                Map.entry("", "{\"choices\":[{\"delta\":{\"reasoning_content\":\"推理\"}}]}"),
                Map.entry("", "{\"choices\":[{\"delta\":{\"content\":\"结果\"}}]}"),
                Map.entry("", "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{\\\"action\\\":\\\"edit\\\",\\\"prompt\\\":\\\"修改\\\"}\"}}]}}]}"),
                Map.entry("", "[DONE]"));
        var rec = handle("openai-chat-completions", lines);
        // tool_calls 仅累积参数，不产出 delta 事件（与前端一致）
        assertThat(rec.types).containsExactly("reasoning", "delta");
        assertThat(rec.datas.get(0)).containsEntry("text", "推理");
        assertThat(rec.datas.get(1)).containsEntry("text", "结果");
    }

    @Test
    void chatCompletionsArrayContentJoinedAndErrorThrows() {
        var lines = List.of(
                Map.entry("", "{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}}]}"),
                Map.entry("error", "{\"error\":{\"message\":\"限流\"}}"));
        assertThatThrownBy(() -> handle("openai-chat-completions", lines))
                .isInstanceOf(AgentStreamTranslator.UpstreamError.class)
                .hasMessageContaining("限流");
    }

    // ===== anthropic-messages =====

    @Test
    void anthropicTranslatesThinkingTextAndPartialJson() throws Exception {
        var lines = List.of(
                Map.entry("", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"text\":\"思考过程\"}}"),
                Map.entry("", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"更多\"}}"),
                Map.entry("", "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"正文\"}}"),
                Map.entry("", "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"续\"}}"),
                Map.entry("", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"action\\\":\\\"generate\\\"\"}}"),
                Map.entry("", "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"}}"),
                Map.entry("", "{\"type\":\"message_stop\"}"));
        AgentStreamTranslator.StreamState state = new AgentStreamTranslator.StreamState();
        Recorder rec = new Recorder();
        for (var line : lines) {
            translator.handle("anthropic-messages", line.getKey(), line.getValue(), state, rec);
        }
        assertThat(state.accumulated.toString()).isEqualTo("正文续");
        assertThat(state.done).isTrue();
        assertThat(state.toolArgs).contains("\"action\":\"generate\"");
        assertThat(rec.types).contains("reasoning", "delta");
    }

    @Test
    void anthropicErrorEventThrows() {
        var lines = List.of(
                Map.entry("", "{\"type\":\"error\",\"error\":{\"message\":\"超时\"}}"));
        assertThatThrownBy(() -> handle("anthropic-messages", lines))
                .isInstanceOf(AgentStreamTranslator.UpstreamError.class)
                .hasMessageContaining("超时");
    }

    // ===== google-gemini =====

    @Test
    void geminiTranslatesThoughtPartsAndFunctionCall() throws Exception {
        var lines = List.of(
                Map.entry("", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"分析\",\"thought\":true},{\"text\":\"正文内容\"}]}}]}"),
                Map.entry("", "{\"candidates\":[{\"content\":{\"parts\":[{\"functionCall\":{\"name\":\"propose_image_action\",\"args\":{\"action\":\"generate\",\"prompt\":\"测试\"}}}]}}]}"));
        AgentStreamTranslator.StreamState state = new AgentStreamTranslator.StreamState();
        Recorder rec = new Recorder();
        for (var line : lines) {
            translator.handle("google-gemini", line.getKey(), line.getValue(), state, rec);
        }
        assertThat(state.accumulated.toString()).isEqualTo("正文内容");
        assertThat(rec.types).containsExactly("reasoning", "delta");
        assertThat(state.toolArgs).contains("\"prompt\":\"测试\"");
    }

    @Test
    void geminiErrorThrows() {
        var lines = List.of(
                Map.entry("", "{\"error\":{\"message\":\"API 密钥无效\"}}"));
        assertThatThrownBy(() -> handle("google-gemini", lines))
                .isInstanceOf(AgentStreamTranslator.UpstreamError.class)
                .hasMessageContaining("API 密钥无效");
    }

    // ===== 提案解析（parseProposalArguments 对拍）=====

    @Test
    void parseProposalExtractsFrontendAgentProposalShape() {
        Map<String, Object> proposal = translator.parseProposal(
                "{\"action\":\"edit\",\"prompt\":\"改图\",\"reason\":\"原因\",\"referenced_image_ids\":[\"a1\",\"b2\"],"
                        + "\"requested_aspect_ratio\":\"16:9\",\"suggested_aspect_ratio\":null,\"requested_output_size\":\"4K\","
                        + "\"temperature\":0.8,\"parallel_count\":2,\"gpt_image_quality\":\"high\","
                        + "\"gpt_image_style\":null,\"gpt_image_background\":\"auto\",\"requested_model_id\":\"m1\"}");
        assertThat(proposal).isNotNull();
        assertThat(proposal.get("action")).isEqualTo("edit");
        assertThat(proposal.get("prompt")).isEqualTo("改图");
        assertThat(proposal.get("referencedImageIds")).isEqualTo(List.of("a1", "b2"));
        assertThat(proposal.get("requestedAspectRatio")).isEqualTo("16:9");
        assertThat(proposal.get("requestedOutputSize")).isEqualTo("4K");
        assertThat(proposal.get("temperature")).isEqualTo(0.8);
        assertThat(proposal.get("parallelCount")).isEqualTo(2.0);
        assertThat(proposal.get("gptImageQuality")).isEqualTo("high");
        assertThat(proposal.get("gptImageBackground")).isEqualTo("auto");
        assertThat(proposal.get("requestedModelId")).isEqualTo("m1");
        // null/缺失字段不出现
        assertThat(proposal).doesNotContainKey("suggestedAspectRatio");
        assertThat(proposal).doesNotContainKey("gptImageStyle");
    }

    @Test
    void parseProposalInvalidOrEmptyPromptReturnsNull() {
        assertThat(translator.parseProposal(null)).isNull();
        assertThat(translator.parseProposal("not-json")).isNull();
        assertThat(translator.parseProposal("{\"action\":\"generate\",\"prompt\":\"\"}")).isNull();
        assertThat(translator.parseProposal("{\"action\":\"generate\"}")).isNull();
    }

    @Test
    void nonJsonSseLineIgnoredAndDoneMarkerNotTranslated() throws Exception {
        var rec = handle("openai-responses", List.of(Map.entry("", "garbage"), Map.entry("", "[DONE]")));
        assertThat(rec.types).isEmpty();
    }
}
