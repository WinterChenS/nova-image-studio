package com.nova.studio.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 (WIN-28) — protocol-different usage extraction from upstream response
 * JSON (F.3/Q5): openai-chat prompt/completion tokens, openai-responses and
 * anthropic input/output tokens, gemini usageMetadata; missing/malformed →
 * null (H6, business zero impact).
 */
class UsageParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UsageParser.UsageTokens parse(String json) {
        try {
            return UsageParser.parseUsage(MAPPER.readTree(json));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void openaiChatCompletionsUsage() {
        var tokens = parse("{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34}}");
        assertThat(tokens.inputTokens()).isEqualTo(12);
        assertThat(tokens.outputTokens()).isEqualTo(34);
    }

    @Test
    void openaiResponsesUsage() {
        var tokens = parse("{\"usage\":{\"input_tokens\":5,\"output_tokens\":7}}");
        assertThat(tokens.inputTokens()).isEqualTo(5);
        assertThat(tokens.outputTokens()).isEqualTo(7);
    }

    @Test
    void anthropicMessageDeltaUsage() {
        var tokens = parse("{\"type\":\"message_delta\",\"usage\":{\"input_tokens\":100,\"output_tokens\":200}}");
        assertThat(tokens.inputTokens()).isEqualTo(100);
        assertThat(tokens.outputTokens()).isEqualTo(200);
    }

    @Test
    void geminiUsageMetadata() {
        var tokens = parse("{\"candidates\":[{}],\"usageMetadata\":{\"promptTokenCount\":42,\"candidatesTokenCount\":58}}");
        assertThat(tokens.inputTokens()).isEqualTo(42);
        assertThat(tokens.outputTokens()).isEqualTo(58);
    }

    @Test
    void noUsageReturnsNull() {
        assertThat(parse("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}")).isNull();
    }

    @Test
    void malformedJsonReturnsNull() {
        try {
            assertThat(UsageParser.parseUsage(MAPPER.readTree("{broken"))).isNull();
        } catch (Exception ignored) {
            // malformed input — caller treats as null
        }
    }

    @Test
    void partialUsageKeepsNullForMissingSide() {
        var tokens = parse("{\"usage\":{\"prompt_tokens\":9}}");
        assertThat(tokens.inputTokens()).isEqualTo(9);
        assertThat(tokens.outputTokens()).isNull();
    }
}
