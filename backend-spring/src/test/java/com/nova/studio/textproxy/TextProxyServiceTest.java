package com.nova.studio.textproxy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1.5 — text proxy target construction and body cleaning (ports of the Node
 * {@code POST /api/nova/proxy/text} routing logic).
 */
class TextProxyServiceTest {

    private final TextProxyService service = new TextProxyService(new ObjectMapper(), 1_800_000);

    @Test
    void googleProtocolBuildsGenerateContentUrlsAndHeaders() {
        TextProxyService.Target t = service.buildTarget("google", "https://generativelanguage.googleapis.com", "gk-1", "gemini-2.5-flash", false);
        assertThat(t.url()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent");
        assertThat(t.headers()).containsEntry("x-goog-api-key", "gk-1");
    }

    @Test
    void googleStreamingUsesAltSse() {
        TextProxyService.Target t = service.buildTarget("google", "https://generativelanguage.googleapis.com", "gk-1", "gemini-2.5-flash", true);
        assertThat(t.url()).isEqualTo("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse");
        assertThat(t.headers()).containsEntry("Accept", "text/event-stream");
    }

    @Test
    void anthropicProtocolUsesMessagesEndpointWithVersionHeader() {
        TextProxyService.Target t = service.buildTarget("anthropic-messages", "https://api.anthropic.com", "ak-1", "claude-3-5-sonnet", false);
        assertThat(t.url()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(t.headers()).containsEntry("x-api-key", "ak-1").containsEntry("anthropic-version", "2023-06-01");
    }

    @Test
    void openaiChatAndResponsesEndpoints() {
        TextProxyService.Target chat = service.buildTarget("openai-chat-completions", "https://api.openai.com", "sk-1", "gpt-4o", false);
        assertThat(chat.url()).isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(chat.headers()).containsEntry("Authorization", "Bearer sk-1");

        TextProxyService.Target responses = service.buildTarget("openai-responses", "https://api.openai.com", "sk-1", "gpt-4o", false);
        assertThat(responses.url()).isEqualTo("https://api.openai.com/v1/responses");
    }

    @Test
    void baseUrlTrailingV1StrippedByNormalization() {
        TextProxyService.Target t = service.buildTarget("openai-chat-completions", "https://my-gateway.example/v1", "sk-1", "m", false);
        assertThat(t.url()).isEqualTo("https://my-gateway.example/v1/chat/completions");
    }

    @Test
    void googleBaseUrlTrailingV1betaStripped() {
        TextProxyService.Target t = service.buildTarget("google", "https://gw.example/v1beta", "k", "m", false);
        assertThat(t.url()).isEqualTo("https://gw.example/v1beta/models/m:generateContent");
    }

    @Test
    void forwardedBodyUsesRequestBodyWhenPresent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("protocol", "openai-chat-completions");
        body.put("apiKey", "sk-x");
        body.set("requestBody", mapper.createObjectNode().set("model", mapper.getNodeFactory().textNode("gpt-4o")));
        assertThat(TextProxyService.forwardedBody(body).get("model").asText()).isEqualTo("gpt-4o");
    }

    @Test
    void forwardedBodyCleansControlFieldsOtherwise() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("protocol", "openai-chat-completions");
        body.put("baseUrl", "https://x");
        body.put("apiKey", "sk-x");
        body.put("model", "gpt-4o");
        body.put("stream", true);
        body.putNull("requestBody");
        body.set("messages", mapper.createArrayNode().add(mapper.createObjectNode().set("role", mapper.getNodeFactory().textNode("user"))));
        var forwarded = TextProxyService.forwardedBody(body);
        assertThat(forwarded.has("protocol")).isFalse();
        assertThat(forwarded.has("baseUrl")).isFalse();
        assertThat(forwarded.has("apiKey")).isFalse();
        assertThat(forwarded.has("model")).isFalse();
        assertThat(forwarded.has("stream")).isFalse();
        assertThat(forwarded.has("requestBody")).isFalse();
        assertThat(forwarded.has("messages")).isTrue();
    }
}
