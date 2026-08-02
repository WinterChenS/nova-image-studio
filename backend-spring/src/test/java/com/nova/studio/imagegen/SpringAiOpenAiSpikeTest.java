package com.nova.studio.imagegen;

import com.openai.client.OpenAIClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiImageModel;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T0.2 — Spring AI 2.0.0 capability verification against a mocked OpenAI-compatible
 * upstream (no real credentials needed):
 * <ul>
 *   <li>OpenAI chat, non-streaming ({@code ChatModel.call})</li>
 *   <li>OpenAI chat, streaming ({@code ChatModel.stream} → {@code Flux<ChatResponse>})</li>
 *   <li>OpenAI image, non-streaming ({@code OpenAiImageModel.call} → {@code ImageResponse})</li>
 * </ul>
 */
class SpringAiOpenAiSpikeTest {

    private MockWebServer server;
    private OpenAIClient client;

    @BeforeEach
    void setUp() {
        server = new MockWebServer();
        client = OpenAiSetup.setupSyncClient(server.url("/v1").toString(), "test-key",
                null, null, null, null, false, false, null,
                Duration.ofSeconds(10), 1, null, null,
                io.micrometer.observation.ObservationRegistry.NOOP, null, List.of());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void chatNonStreaming() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"id":"chatcmpl-1","object":"chat.completion","created":1,"model":"gpt-4o-mini",
                         "choices":[{"index":0,"message":{"role":"assistant","content":"Hello!"},"finish_reason":"stop"}],
                         "usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}
                        """));

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(client)
                .options(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .baseUrl(server.url("/v1").toString())
                        .apiKey("test-key")
                        .build())
                .build();

        ChatResponse response = model.call(new Prompt("hi"));
        assertThat(response.getResult().getOutput().getText()).isEqualTo("Hello!");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    void chatStreaming() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("""
                        data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}

                        data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}

                        data: {"id":"chatcmpl-2","object":"chat.completion.chunk","created":1,"model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                        data: [DONE]

                        """));

        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiClient(client)
                .options(OpenAiChatOptions.builder()
                        .model("gpt-4o-mini")
                        .baseUrl(server.url("/v1").toString())
                        .apiKey("test-key")
                        .build())
                .build();

        String streamed = model.stream(new Prompt("hi"))
                .map(r -> r.getResult() != null && r.getResult().getOutput() != null
                        ? r.getResult().getOutput().getText() : "")
                .collectList()
                .block()
                .stream()
                .reduce("", String::concat);

        assertThat(streamed).isEqualTo("Hello");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/chat/completions");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-key");
    }

    @Test
    void imageNonStreaming() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"created":1,"data":[{"b64_json":"QUJDREVG"}]}
                        """));

        OpenAiImageModel model = OpenAiImageModel.builder()
                .openAiClient(client)
                .options(OpenAiImageOptions.builder()
                        .model("gpt-image-1")
                        .baseUrl(server.url("/v1").toString())
                        .apiKey("test-key")
                        .build())
                .build();

        ImageResponse response = model.call(new org.springframework.ai.image.ImagePrompt("a cat"));
        assertThat(response.getResult().getOutput().getB64Json()).isEqualTo("QUJDREVG");

        RecordedRequest req = server.takeRequest();
        assertThat(req.getPath()).isEqualTo("/v1/images/generations");
        assertThat(req.getHeader("Authorization")).isEqualTo("Bearer test-key");
    }
}
